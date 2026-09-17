package dev.example;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Executa uma senoide de 440 Hz em uma instância embutida do Csound 7. */
public final class Main {
    private static final String CSD = """
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
                aSenoide oscili 0.2, 440
                out aSenoide
            endin
            </CsInstruments>
            <CsScore>
            i 1 0 2
            </CsScore>
            </CsoundSynthesizer>
            """;

    private Main() {
    }

    public static void main(String[] args) throws Throwable {
        loadCsoundLibrary();

        Linker linker = Linker.nativeLinker();
        SymbolLookup symbols = SymbolLookup.loaderLookup();

        MethodHandle create = downcall(linker, symbols, "csoundCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        MethodHandle compileCsd = downcall(linker, symbols, "csoundCompileCSD",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        MethodHandle start = downcall(linker, symbols, "csoundStart",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle performKsmps = downcall(linker, symbols, "csoundPerformKsmps",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle reset = downcall(linker, symbols, "csoundReset",
                FunctionDescriptor.ofVoid(ADDRESS));
        MethodHandle destroy = downcall(linker, symbols, "csoundDestroy",
                FunctionDescriptor.ofVoid(ADDRESS));

        MemorySegment csound = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
        if (csound.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("O Csound não pôde ser instanciado.");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment csd = arena.allocateFrom(CSD);
            check((int) compileCsd.invokeExact(csound, csd, 1, 0), "compilar o CSD");
            check((int) start.invokeExact(csound), "iniciar o engine");

            System.out.println("Csound 7 tocando uma senoide de 440 Hz por 2 segundos...");
            while ((int) performKsmps.invokeExact(csound) == 0) {
                // O Csound produz um bloco de áudio a cada iteração.
            }
        } finally {
            reset.invokeExact(csound);
            destroy.invokeExact(csound);
        }
    }

    private static MethodHandle downcall(
            Linker linker, SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = symbols.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Símbolo não encontrado: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    private static void loadCsoundLibrary() {
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
