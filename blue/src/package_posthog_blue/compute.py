"""One node through the shared compute lifecycle; application policy only."""
import json
from pathlib import Path
from colors_compute import orchestrate, plan_deployment, read_deployment, source_cidrs
from colors_compute.rendering import backend_plan
from colors_compute.planning import validate_deployment

TOPOLOGY = [{'role': None, 'count': 1}]

def requirements(opts):
    ssh = source_cidrs(opts, 'ssh-sources', 'posthog-ssh-sources')
    http = source_cidrs(opts, 'http-sources', 'posthog-http-sources')
    return {'single_host': True, 'legacy_state_keys': [opts['profile'] + '/posthog-infrastructure.tfstate'],
            'security': {'ingress': [{'id': 'ssh', 'protocol': 'tcp', 'from_port': 22, 'to_port': 22, 'sources': ssh}]
                        + [{'id': 'http-' + str(port), 'protocol': 'tcp', 'from_port': port, 'to_port': port, 'sources': http} for port in (80,443) if http], 'egress': 'all', 'private_filter': False}}

def errors(opts):
    try:
        validate_deployment(opts, TOPOLOGY, requirements(opts))
        backend_plan(opts, opts['profile'] + '/compute/shared.tfstate')
        return []
    except Exception:
        return ['invalid compute deployment requirements']

def planned(opts):
    return plan_deployment(opts, TOPOLOGY, requirements(opts))

def node(result):
    nodes = result.get('cluster', {}).get('nodes', [])
    if len(nodes) != 1 or not nodes[0].get('ip'):
        raise ValueError('compute node unavailable')
    return nodes[0]

def attach(opts, result):
    if result.get('status') not in ('planned', 'ready', 'present', 'destroyed'):
        return {**opts, 'blue/exit': 1, 'blue/err': '\n'.join(result.get('errors', [])) or 'compute lifecycle refused'}
    if result['status'] == 'destroyed':
        return {**opts, 'blue/exit': 0, 'posthog/already-destroyed': True}
    params = node(result)
    path = result.get('key', {}).get('private_key_path')
    if path and (opts.get('blue/event') == 'build' or opts.get('blue/dry-run')):
        path = path.replace('$HOME', '/home/build-placeholder')
    return {**opts, **params, 'colors-compute/cluster': result['cluster'], 'blue/exit': 0,
            **({'ssh-private-key-path': path} if path else {})}

async def infrastructure_step(opts):
    try:
        planning = opts.get('blue/event') == 'build' or opts.get('blue/dry-run')
        result = planned(opts) if planning else await orchestrate(opts, TOPOLOGY, requirements(opts))
        if planning:
            root = Path(opts['workdir']) / opts['profile'] / 'compute'
            stages = {'shared': result['documents']['shared'], **{'nodes/'+key: docs for key, docs in result['documents']['nodes'].items()}}
            for stage, documents in stages.items():
                directory = root / stage
                directory.mkdir(parents=True, exist_ok=True)
                state_key = result["state_keys"]["shared"] if stage == "shared" else result["state_keys"]["nodes"][stage.removeprefix("nodes/")]
                documents = {**documents, "backend.tf.json": backend_plan(opts, state_key)["config"]}
                for filename, document in documents.items():
                    (directory / filename).write_text(json.dumps(document, sort_keys=True, indent=2) + '\n')
        return attach(opts, result)
    except Exception:
        return {**opts, 'blue/exit': 1, 'blue/err': 'invalid compute deployment requirements'}

async def load(opts, environment=None):
    return attach(opts, await read_deployment(opts, environment, None, requirements(opts)))
