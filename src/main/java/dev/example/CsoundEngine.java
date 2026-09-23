package dev.example;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Adaptador mínimo para executar uma instância contínua do Csound 7. */
final class CsoundEngine {
    private static final Map<String, String> OSCILLATORS = Map.of(
            "sine", "aBase phasor kFrequency\n                aOnda = sin(2 * $M_PI * (aBase + kPhase)) * kVolume",
            "saw", "aOnda vco2 kVolume, kFrequency, 16, 0.5, kPhase",
            "square", "aOnda vco2 kVolume, kFrequency, 26, 0.5, kPhase",
            "triangle", "aOnda vco2 kVolume, kFrequency, 28, 0.5, kPhase"
    );

    private static final String CSD_TEMPLATE = """
            <CsoundSynthesizer>
            <CsOptions>
            -odac -d
            </CsOptions>
            <CsInstruments>
            sr = 48000
            ksmps = 64
            nchnls = 1
            0dbfs = 1
            chnk "frequency", 1
            chnk "volume", 1
            chnk "phase", 1

            instr 1
                kFrequency chnget "frequency"
                kVolume chnget "volume"
                kPhase chnget "phase"
                %s
                out aOnda
            endin
            </CsInstruments>
            <CsScore>
            i 1 0 86400
            </CsScore>
            </CsoundSynthesizer>
            """;

    private final MethodHandle create;
    private final MethodHandle compileCsd;
    private final MethodHandle startCsound;
    private final MethodHandle performKsmps;
    private final MethodHandle setControlChannel;
    private final MethodHandle reset;
    private final MethodHandle destroy;
    private volatile boolean stopRequested;
    private Thread worker;
    private MemorySegment activeCsound;

    CsoundEngine() {
        loadLibrary();
        Linker linker = Linker.nativeLinker();
        SymbolLookup symbols = SymbolLookup.loaderLookup();
        create = downcall(linker, symbols, "csoundCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        compileCsd = downcall(linker, symbols, "csoundCompileCSD",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        startCsound = downcall(linker, symbols, "csoundStart", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        performKsmps = downcall(linker, symbols, "csoundPerformKsmps", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        setControlChannel = downcall(linker, symbols, "csoundSetControlChannel",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE));
        reset = downcall(linker, symbols, "csoundReset", FunctionDescriptor.ofVoid(ADDRESS));
        destroy = downcall(linker, symbols, "csoundDestroy", FunctionDescriptor.ofVoid(ADDRESS));
    }

    synchronized void start(String waveform, double frequency, double volume, double phase) throws Throwable {
        if (worker != null) throw new IllegalStateException("O Csound já está em execução.");
        validateRange("Frequência", frequency, 20, 2_000);
        validateRange("Volume", volume, 0, 0.5);
        validateRange("Fase", phase, 0, 1);

        String format = OSCILLATORS.get(waveform);
        if (format == null) throw new IllegalArgumentException("Forma de onda inválida.");
        MemorySegment csound = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
        if (csound.equals(MemorySegment.NULL)) throw new IllegalStateException("O Csound não pôde ser instanciado.");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment csd = arena.allocateFrom(CSD_TEMPLATE.formatted(format));
            check((int) compileCsd.invokeExact(csound, csd, 1, 0), "compilar o CSD");
            setChannel(csound, "frequency", frequency);
            setChannel(csound, "volume", volume);
            setChannel(csound, "phase", phase);
            check((int) startCsound.invokeExact(csound), "iniciar o engine");
        } catch (Throwable error) {
            destroy.invokeExact(csound);
            throw error;
        }

        stopRequested = false;
        activeCsound = csound;
        worker = Thread.ofVirtual().name("csound-performance").start(() -> perform(csound));
    }

    synchronized void update(double frequency, double volume, double phase) throws Throwable {
        validateRange("Frequência", frequency, 20, 2_000);
        validateRange("Volume", volume, 0, 0.5);
        validateRange("Fase", phase, 0, 1);
        if (activeCsound == null) throw new IllegalStateException("O Csound não está em execução.");
        setChannel(activeCsound, "frequency", frequency);
        setChannel(activeCsound, "volume", volume);
        setChannel(activeCsound, "phase", phase);
    }

    void stop() throws InterruptedException {
        Thread activeWorker;
        synchronized (this) {
            if (worker == null) throw new IllegalStateException("O Csound não está em execução.");
            stopRequested = true;
            activeCsound = null;
            activeWorker = worker;
        }
        activeWorker.join(3_000);
        if (activeWorker.isAlive()) throw new IllegalStateException("O Csound não parou no tempo esperado.");
    }

    private void perform(MemorySegment csound) {
        try {
            while (!stopRequested && (int) performKsmps.invokeExact(csound) == 0) {
                // Produz um bloco de áudio por iteração.
            }
        } catch (Throwable error) {
            error.printStackTrace();
        } finally {
            try {
                reset.invokeExact(csound);
                destroy.invokeExact(csound);
            } catch (Throwable error) {
                error.printStackTrace();
            }
            synchronized (this) {
                activeCsound = null;
                worker = null;
            }
        }
    }

    private void setChannel(MemorySegment csound, String name, double value) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            setControlChannel.invokeExact(csound, arena.allocateFrom(name), value);
        }
    }

    private static void validateRange(String name, double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " fora da faixa permitida.");
        }
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup symbols, String name,
                                         FunctionDescriptor descriptor) {
        MemorySegment symbol = symbols.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Símbolo não encontrado: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    private static void loadLibrary() {
        String explicitPath = System.getProperty("csound.library.path");
        if (explicitPath != null && !explicitPath.isBlank()) {
            System.load(Path.of(explicitPath).toAbsolutePath().toString());
            return;
        }
        UnsatisfiedLinkError lastError = null;
        for (String name : new String[]{"csound64", "csound"}) {
            try {
                System.loadLibrary(name);
                return;
            } catch (UnsatisfiedLinkError error) {
                lastError = error;
            }
        }
        throw new UnsatisfiedLinkError("Biblioteca do Csound 7 não encontrada: "
                + (lastError == null ? "erro desconhecido" : lastError.getMessage()));
    }

    private static void check(int result, String action) {
        if (result != 0) throw new IllegalStateException("Falha ao " + action + " (código " + result + ").");
    }
}
