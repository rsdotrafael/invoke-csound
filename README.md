# Java + Csound 7

Projeto Java mínimo que carrega a biblioteca nativa do Csound 7, instancia o
engine e produz no dispositivo de áudio uma senoide de 440 Hz por 2 segundos.

O acesso à API C é feito com a Foreign Function & Memory API, nativa desde o
Java 22. Não há dependências Java em tempo de execução.

## Pré-requisitos

- JDK 22 ou mais recente;
- Csound 7 instalado;
- a pasta de `csound64.dll` (Windows), `libcsound64.so`/`libcsound.so` (Linux)
  ou `libcsound64.dylib` (macOS) disponível no caminho de bibliotecas do sistema.

## Executar sem Maven

No PowerShell:

```powershell
javac --release 22 -d out src/main/java/dev/example/Main.java
java --enable-native-access=ALL-UNNAMED -cp out dev.example.Main
```

Se a biblioteca não estiver no caminho padrão, informe o arquivo explicitamente:

```powershell
java --enable-native-access=ALL-UNNAMED `
  -Dcsound.library.path="C:\caminho\para\csound64.dll" `
  -cp out dev.example.Main
```

Também é possível compilar com `mvn package` e executar a classe da mesma forma,
usando `target/classes` no classpath.

O volume do oscilador está em `0.2` para evitar uma saída excessivamente alta.
