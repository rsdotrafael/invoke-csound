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
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Adaptador mínimo para a API C do Csound 7. */
final class CsoundEngine {
    private static final Map<String, String> OSCILLATORS = Map.of(
            "sine", "aOnda oscili 0.2, 440",
            "saw", "aOnda vco2 0.2, 440, 0",
            "square", "aOnda vco2 0.2, 440, 10",
            "triangle", "aOnda vco2 0.2, 440, 12"
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

            instr 1
                %s
                out aOnda
            endin
            </CsInstruments>
            <CsScore>
            i 1 0 2
            </CsScore>
            </CsoundSynthesizer>
            """;

    private final MethodHandle create;
    private final MethodHandle compileCsd;
    private final MethodHandle start;
    private final MethodHandle performKsmps;
    private final MethodHandle reset;
    private final MethodHandle destroy;

    CsoundEngine() {
        loadLibrary();
        Linker linker = Linker.nativeLinker();
        SymbolLookup symbols = SymbolLookup.loaderLookup();
        create = downcall(linker, symbols, "csoundCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        compileCsd = downcall(linker, symbols, "csoundCompileCSD",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        start = downcall(linker, symbols, "csoundStart", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        performKsmps = downcall(linker, symbols, "csoundPerformKsmps", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        reset = downcall(linker, symbols, "csoundReset", FunctionDescriptor.ofVoid(ADDRESS));
        destroy = downcall(linker, symbols, "csoundDestroy", FunctionDescriptor.ofVoid(ADDRESS));
    }

    synchronized void play440Hz(String waveform) throws Throwable {
        String oscillator = OSCILLATORS.get(waveform);
        if (oscillator == null) {
            throw new IllegalArgumentException("Forma de onda inválida: " + waveform);
        }

        MemorySegment csound = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
        if (csound.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("O Csound não pôde ser instanciado.");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment csd = arena.allocateFrom(CSD_TEMPLATE.formatted(oscillator));
            check((int) compileCsd.invokeExact(csound, csd, 1, 0), "compilar o CSD");
            check((int) start.invokeExact(csound), "iniciar o engine");
            while ((int) performKsmps.invokeExact(csound) == 0) {
                // Produz um bloco de áudio por iteração.
            }
        } finally {
            reset.invokeExact(csound);
            destroy.invokeExact(csound);
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
        throw new UnsatisfiedLinkError("""
                A biblioteca nativa do Csound 7 não foi encontrada.
                Adicione sua pasta ao PATH ou execute com:
                  -Dcsound.library.path=C:\\caminho\\para\\csound64.dll
                Detalhe: %s
                """.formatted(lastError == null ? "desconhecido" : lastError.getMessage()));
    }

    private static void check(int result, String action) {
        if (result != 0) {
            throw new IllegalStateException("Falha ao " + action + " (código " + result + ").");
        }
    }
}
