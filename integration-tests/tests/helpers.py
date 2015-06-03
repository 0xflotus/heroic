import Queue as q
import contextlib
import functools
import json
import requests
import select
import signal
import socket
import subprocess as sp
import tempfile
import threading as t
import yaml
import sys

MAIN_CLASS = "com.spotify.heroic.HeroicService"
DEV_NULL = open("/dev/null")
BASE_PORT = 12345
PING_PORT = 12021


class HeroicAPI(object):
    def __init__(self, p, port):
        self._p = p
        self._s = requests.Session()
        self._base = "http://localhost:{}".format(port)

    # Popen API
    def terminate(self):
        return self._p.terminate()

    def poll(self):
        return self._p.poll()

    def wait(self):
        return self._p.wait()

    def reap(self):
        self.poll()

        if not self.returncode:
            self.terminate()
            self.wait()

    @property
    def returncode(self):
        return self._p.returncode

    @property
    def uri(self):
        return self._base

    def _url(self, path):
        return "{}/{}".format(self._base, path)

    def _post(self, path, **kwargs):
        return self._s.post(self._url(path), **kwargs)

    def _get(self, path):
        return self._s.get(self._url(path))

    def _get_json(self, path):
        r = self._get(path)
        return json.loads(r.content)

    def _post_json(self, path, data):
        headers = {'content-type': 'application/json'}
        content = json.dumps(data)
        return self._post(path, data=content, headers=headers)

    def utils_wait(self):
        return self._get('utils/wait')

    def status(self):
        return self._get_json('status')

    def cluster_status(self):
        return self._get_json('cluster/status')

    def cluster_add_node(self, uri):
        return self._post_json('cluster/nodes', uri)


class Settings(object):
    def __init__(self, **kwargs):
        self.heroic_jar = kwargs.pop('heroic_jar', None)


S = Settings()


def heroic(identifier, *args, **kwargs):
    global S

    if S.heroic_jar is None:
        raise Exception("setup() has not been called, run through bin/run")

    config = kwargs.pop("config", None)
    debug = kwargs.pop("debug", False)

    instance_args = ["--startup-ping", "udp://localhost:{}".format(PING_PORT),
                     "--startup-id", str(identifier),
                     "--port", str(0)]

    if debug:
        out = sys.stdout
    else:
        out = DEV_NULL

    if config:
        instance_args += [config]

    return sp.Popen(
        ["java", "-cp", S.heroic_jar, MAIN_CLASS] +
        instance_args + list(args), stdout=out, stderr=out)


def setup(**kwargs):
    global S
    S = Settings(**kwargs)


class TimeoutException(Exception):
    pass


def wait_for(*children):
    tasks = q.Queue()

    def wait_for_child(child):
        try:
            child.wait()
        finally:
            tasks.put(child.returncode)

    procs = []

    for child in children:
        p = t.Thread(target=wait_for_child, args=(child,))
        p.start()
        procs.append(p)

    results = []

    count = len(children)

    while count > 0:
        # Make sure to only block for a short while to allow signal handlers to
        # fire.
        try:
            result = tasks.get(True, 0.1)
        except q.Empty:
            continue

        results.append(result)
        count = count - 1

    for p in procs:
        p.join()

    if not all(r == 0 for r in results):
        raise Exception(
            "Not all child processes exited gracefully: {}".format(
                repr(results)))


def timeout(seconds=5, error_message="Timeout in Test"):
    """
    Decorator that restricts the amount of time that a decorated method is
    allowed to block.

    The decorated method must properly handle cleanup on exception, since this
    will cause a TimeoutException to be raised in the decorated context.
    """

    def decorator(func):
        def _handle_timeout(signum, frame):
            raise TimeoutException(error_message)

        def wrapper(*args, **kwargs):
            signal.signal(signal.SIGALRM, _handle_timeout)
            signal.alarm(seconds)

            try:
                return func(*args, **kwargs)
            finally:
                signal.alarm(0)

        return functools.wraps(func)(wrapper)

    return decorator


def setup_apis(configs, sock, debug=False):
    """
    Setup configurations and wait until all processes has responded with a ping.
    """

    procs = dict()
    tempfiles = []

    try:
        for i, c in enumerate(configs):
            t = tempfile.NamedTemporaryFile(prefix="heroic-config-")
            yaml.dump(c, t)
            tempfiles.append(t)
            procs[i] = heroic(i, config=t.name, debug=debug)

        apis = dict()

        # read ping:ed configuration.
        for (identifier, api) in read_config(procs, sock):
            apis[identifier] = api
    except:
        for n, p in procs.items():
            p.poll()

            if not p.returncode:
                p.terminate()

        for n, p in procs.items():
            p.wait()

        raise
    finally:
        for t in tempfiles:
            t.close()

    return apis.values()


def read_config(procs, sock):
    count = 0

    while count < len(procs):
        xlist = [sock]
        rlist = [sock]

        rl, wl, xl = select.select(rlist, [], xlist, 0.1)

        if len(xl) > 0:
            raise Exception("Error in socket")

        if len(rl) == 0 and len(xl) == 0:
            for n, p in procs.items():
                p.poll()

                if p.returncode:
                    raise Exception("One or more processes exited prematurely")

            continue

        if len(rl) > 0:
            d, sender = sock.recvfrom(2 ** 16)
            body = json.loads(d)

            identifier = int(body["id"])
            port = body["port"]
            p = procs[identifier]

            if not p:
                raise Exception("No such instance '{}'".format(identifier))

            count += 1
            yield identifier, HeroicAPI(p, port)


@contextlib.contextmanager
def managed(*configs, **kwargs):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    debug = kwargs.pop("debug", False)

    try:
        sock.bind(('localhost', PING_PORT))
    except:
        sock.close()
        raise

    try:
        apis = setup_apis(configs, sock, debug=debug)
    finally:
        sock.close()

    try:
        # wait until both processed has started.
        for p in apis:
            p.utils_wait()

        yield tuple(apis)

        for p in apis:
            p.poll()

            if not p.returncode:
                p.terminate()

        for p in apis:
            p.wait()
    finally:
        for p in apis:
            p.reap()
