# quic_test

Приложение для проверки протокола quic
https://ru.wikipedia.org/wiki/QUIC

Исходники библиотек

скачать
https://github.com/kachayev/quiche4j

установить rust
```
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

установить os-maven-plugin
```
git clone git@github.com:trustin/os-maven-plugin.git
mvn install
```
установить cmake

```
sudo apt  install cmake
```

обновить rust до нестабильной ветки
```
rustup install nightly
rustup default nightly
rustup update nightly
```
 

в проекте Quiche4j/JNI пытаемся скомпилировать, Обламываемся
удаляем из помника
```
<exec executable="${exec.executable}" failonerror="true" resolveexecutable="false">
<arg line="+nightly build --lib --${native.buildMode} --color always --target-dir ${native.targetDir} --out-dir ${nativeLibOnlyDir} -Z unstable-options" />
</exec>
```

и вручную билдим библиотеку rust
```
cargo +nightly build --lib --release --color always --target-dir target --out-dir /mnt/MyProjects/git/quiche4j/quiche4j-jni/target/native-lib-only -Z unstable-options
```

дальше ставим jni и core.

