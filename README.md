# Java + Csound 7

Aplicação Java mínima com uma interface web que aciona o Csound 7 nativo e
produz no dispositivo de áudio uma senoide de 440 Hz por 2 segundos.

O browser chama um endpoint HTTP local; o processo Java instancia e controla o
engine pela Foreign Function & Memory API, nativa desde o Java 22. Não há
dependências Java em tempo de execução.

## Pré-requisitos

- JDK 22 ou mais recente;
- Csound 7 instalado;
- a biblioteca nativa do Csound disponível no caminho do sistema.

## Executar

No PowerShell:

```powershell
.\run.cmd
```

Abra <http://localhost:8080> e clique em **Tocar 440 Hz**. Use `Ctrl+C` no
terminal para encerrar o servidor.

O script compila a aplicação, copia a página para o classpath, localiza a DLL
do Csound e inicia o servidor. Se a DLL não for localizada automaticamente,
defina antes seu caminho completo:

```powershell
$env:CSOUND_LIBRARY_PATH = "D:\Program Files\Csound7\bin\csound64.dll"
.\run.cmd
```

O servidor escuta somente em `127.0.0.1`, portanto não fica exposto para outras
máquinas da rede. O volume do oscilador está em `0.2`.
