import json
import os
from pathlib import Path
import subprocess
import shutil
import shlex
import signal
import sys
import tempfile

SOURCE: Path = Path(__file__).resolve().parent
ROOT: Path = SOURCE.parents[2]
BUILD: Path = ROOT / 'build' / 'client-automator'

PROBE_TEST: str = r'''
package art.arcane.automator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class CaptureProbeFailureTest {
    private static final Method PROBE;

    static {
        try {
            PROBE = LiveCapture.class.getDeclaredMethod("probeEncoder", Path.class);
            PROBE.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        require("h264_videotoolbox".equals(probe(directory.resolve("hardware"))), "Hardware encoder was not selected");
        require("libx264".equals(probe(directory.resolve("software"))), "Software fallback was not selected");
        expectFailure(directory.resolve("failure"), "code 7");
        expectFailure(directory.resolve("large"), "exceeds");
        expectFailure(directory.resolve("missing"), "Cannot list");
        long started = System.nanoTime();
        expectFailure(directory.resolve("stall-timeout"), "longer than");
        require((System.nanoTime() - started) / 1.0e9 < 15.0, "Probe timeout did not bound output reading and cleanup");
        requireExited(directory.resolve("stall-timeout.pid"));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean preservedInterrupt = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                probe(directory.resolve("stall-interrupt"));
            } catch (Throwable caught) {
                failure.set(caught);
                preservedInterrupt.set(Thread.currentThread().isInterrupted());
            }
        });
        Path pid = directory.resolve("stall-interrupt.pid");
        long deadline = System.nanoTime() + 5_000_000_000L;
        try {
            while (!Files.exists(pid) && worker.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            require(Files.exists(pid), "Interrupted probe did not start its owned process");
        } finally {
            worker.interrupt();
            worker.join(5_000L);
        }
        require(!worker.isAlive(), "Interrupted probe failed to finish cleanup");
        require(failure.get() instanceof IllegalStateException, "Interrupted probe lost its contextual failure");
        require(failure.get().getCause() instanceof InterruptedException, "Interrupted probe lost its cause");
        require(preservedInterrupt.get(), "Probe cleanup cleared interrupt status");
        requireExited(pid);
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            require(files.noneMatch(path -> path.getFileName().toString().startsWith("instance-automator-encoders-")),
                    "Encoder probe left temporary output behind");
        }
        System.out.println("Encoder probe success, exit failure, output limit, timeout, interruption and process cleanup passed");
    }

    private static String probe(Path executable) throws Exception {
        try {
            return (String) PROBE.invoke(null, executable);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failure;
        }
    }

    private static void expectFailure(Path executable, String message) throws Exception {
        try {
            probe(executable);
        } catch (IllegalStateException failure) {
            require(failure.getMessage().contains(executable.toString()), "Probe failure lost executable context");
            require(failure.getCause() != null, "Probe failure lost its cause");
            require(failure.toString().contains(message) || failure.getCause().toString().contains(message),
                    "Unexpected probe failure: " + failure);
            return;
        }
        throw new AssertionError("Invalid encoder probe was accepted: " + executable);
    }

    private static void requireExited(Path pidPath) throws Exception {
        long pid = Long.parseLong(Files.readString(pidPath));
        require(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false, "Owned probe still alive: " + pid);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
'''

ALLOCATION_TEST: str = r'''
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class CaptureAllocationFailureTest {
    private static final int GPU_BUFFERS = 3;
    private final ArrayDeque<GpuBuffer> idleBuffers = new ArrayDeque<>();
    private final Process process;
    private final Path log;
    private final Path executable;

    private CaptureAllocationFailureTest(Path directory) throws IOException {
        log = directory.resolve("capture.log");
        executable = directory.resolve("missing-encoder");
        int frameBytes = 4;
__ALLOCATION__
    }

    private List<String> command() {
        return List.of(executable.toString());
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        for (int failAt : List.of(1, 2)) {
            RenderSystem.device = new GpuDevice(failAt, failAt == 2);
            try {
                new CaptureAllocationFailureTest(directory);
                throw new AssertionError("GPU allocation failure was accepted");
            } catch (IllegalStateException failure) {
                require(failure.getMessage().equals("allocation"), "Cleanup masked GPU allocation failure");
                require(failure.getSuppressed().length == (failAt == 2 ? 1 : 0), "GPU close failure was not preserved");
                require(RenderSystem.device.buffers.size() == failAt, "Unexpected allocated buffer count");
                require(RenderSystem.device.buffers.stream().allMatch(buffer -> buffer.closed), "Partial GPU allocation leaked a buffer");
            }
        }
        RenderSystem.device = new GpuDevice(-1, true);
        try {
            new CaptureAllocationFailureTest(directory);
            throw new AssertionError("Encoder launch failure was accepted");
        } catch (IOException failure) {
            require(failure.getSuppressed().length == 1, "GPU close failure masked encoder launch failure");
            require(RenderSystem.device.buffers.size() == 3, "Unexpected launch allocation count");
            require(RenderSystem.device.buffers.stream().allMatch(buffer -> buffer.closed), "Encoder launch failure leaked a buffer");
        }
        System.out.println("Partial GPU allocation and encoder launch failures release every buffer and retain cleanup errors");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RenderSystem {
        private static GpuDevice device;

        private static GpuDevice getDevice() {
            return device;
        }
    }

    private static final class GpuDevice {
        private final int failAt;
        private final boolean closeFailure;
        private final List<GpuBuffer> buffers = new ArrayList<>();

        private GpuDevice(int failAt, boolean closeFailure) {
            this.failAt = failAt;
            this.closeFailure = closeFailure;
        }

        private GpuBuffer createBuffer(Supplier<String> label, int flags, int size) {
            if (buffers.size() == failAt) {
                throw new IllegalStateException("allocation");
            }
            GpuBuffer buffer = new GpuBuffer(closeFailure && buffers.isEmpty());
            buffers.add(buffer);
            return buffer;
        }
    }

    private static final class GpuBuffer {
        private static final int USAGE_MAP_READ = 1;
        private static final int USAGE_COPY_DST = 2;
        private final boolean closeFailure;
        private boolean closed;

        private GpuBuffer(boolean closeFailure) {
            this.closeFailure = closeFailure;
        }

        private void close() {
            closed = true;
            if (closeFailure) {
                throw new IllegalStateException("close");
            }
        }
    }
}
'''

FAKE_ENCODER: str = '''import os
from pathlib import Path
import signal
import sys
import time

mode: str = sys.argv[1]
directory: Path = Path(sys.argv[2])
(directory / (mode + '.pid')).write_text(str(os.getpid()))
if mode.startswith('stall-'):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    time.sleep(60)
elif mode == 'failure':
    print('synthetic encoder failure', flush=True)
    raise SystemExit(7)
elif mode == 'large':
    sys.stdout.write('x' * (2 * 1024 * 1024))
else:
    print(' V..... ' + ('h264_videotoolbox' if mode == 'hardware' else 'libx264') + ' synthetic encoder')
'''


def run_capture_failure_tests(java: str, javac: str, classpath: str, classes: Path, build: Path) -> None:
    with tempfile.TemporaryDirectory(prefix='capture-failures-', dir=build) as scratch:
        directory: Path = Path(scratch)
        temporary: Path = directory / 'temporary'
        temporary.mkdir()
        fixture: Path = directory / 'encoder.py'
        fixture.write_text(FAKE_ENCODER)
        for mode in ('hardware', 'software', 'failure', 'large', 'stall-timeout', 'stall-interrupt'):
            executable: Path = directory / mode
            executable.write_text('#!/bin/sh\nexec ' + shlex.join([sys.executable, str(fixture), mode, str(directory)]) + '\n')
            executable.chmod(0o700)
        probe_source: Path = directory / 'CaptureProbeFailureTest.java'
        probe_source.write_text(PROBE_TEST)
        subprocess.run([javac, '--release', '25', '-proc:none', '-parameters', '-cp', classpath, '-d', str(classes),
                        str(probe_source)], check=True)
        try:
            subprocess.run([java, '-Djava.io.tmpdir=' + str(temporary), '-cp', str(classes) + os.pathsep + classpath,
                            'art.arcane.automator.CaptureProbeFailureTest', str(directory)], check=True, timeout=40)
        finally:
            for pid_path in directory.glob('stall-*.pid'):
                try:
                    os.kill(int(pid_path.read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    pass
        version: str = json.loads((build / 'compile-classpath.json').read_text())['components']['net.minecraft']
        source: Path = SOURCE / ('26.2/java' if version == '26.2' else 'java') / 'art/arcane/automator/LiveCapture.java'
        text: str = source.read_text()
        start: int = text.index('        try {\n            GpuDevice device = RenderSystem.getDevice();')
        end: int = text.index('        startNanos = System.nanoTime();', start)
        allocation_source: Path = directory / 'CaptureAllocationFailureTest.java'
        allocation_source.write_text(ALLOCATION_TEST.replace('__ALLOCATION__', text[start:end]))
        subprocess.run([javac, '--release', '25', '-proc:none', '-parameters', '-d', str(classes), str(allocation_source)], check=True)
        subprocess.run([java, '-cp', str(classes), 'CaptureAllocationFailureTest', str(directory)], check=True, timeout=10)


def main() -> int:
    version: str = os.environ.get('AUTOMATOR_MINECRAFT_VERSION', '26.3')
    if version not in ('26.2', '26.3'):
        raise ValueError('Unsupported client game version: ' + version)
    build: Path = BUILD / version if version != '26.3' else BUILD
    document: dict = json.loads((build / 'compile-classpath.json').read_text())
    if document['components']['net.minecraft'] != version:
        raise ValueError('Client test classpath does not match requested game version')
    classpath: str = str(build / 'InstanceAutomator.jar') + os.pathsep + document['classpath']
    classes: Path = build / 'tests'
    if classes.is_dir():
        shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    java_home: str | None = os.environ.get('JAVA_HOME')
    javac: str = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    java: str = str(Path(java_home) / 'bin/java') if java_home else 'java'
    test_sources: list[Path] = sorted((SOURCE / 'tests' / 'java').rglob('*.java'))
    if version == '26.2':
        test_sources = [path for path in test_sources if path.name in ('LiveCaptureSettingsTest.java', 'LiveCaptureTimelineTest.java', 'FrameTimingsTest.java', 'IrisStatusTest.java', 'CrossingCostsTest.java')]
    subprocess.run([javac, '--release', '25', '-proc:none', '-parameters', '-cp', classpath, '-d', str(classes),
                    *map(str, test_sources)], check=True)
    test_classpath: str = str(classes) + os.pathsep + classpath
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.FrameTimingsTest'], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.CrossingCostsTest'], check=True)
    subprocess.run([java, '-Dautomator.crossingCosts=true', '-cp', test_classpath, 'art.arcane.automator.CrossingCostsTest'], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.IrisStatusTest'], check=True)
    if version == '26.3':
        subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.LoopbackServerTest'], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.LiveCaptureSettingsTest', str(build / 'validation')], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.LiveCaptureTimelineTest'], check=True)
    run_capture_failure_tests(java, javac, classpath, classes, build)
    if version == '26.2':
        return 0
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.HiddenRendererSettingsTest'], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.WormholesStatusTest'], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.PortalBlockStatusTest'], check=True)
    subprocess.run([java, '-cp', test_classpath, 'art.arcane.automator.TextInputPlanTest'], check=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
