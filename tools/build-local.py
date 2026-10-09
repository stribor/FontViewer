#!/usr/bin/env python3
"""Offline build using an installed IDE and Kotlin compiler. No runtime is bundled."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ide", type=Path, required=True, help="IDE .app or installation directory")
parser.add_argument("--kotlin-home", type=Path, required=True, help="Kotlin distribution containing lib/kotlin-compiler.jar")
parser.add_argument("--test", action="store_true", help="Run core and canvas regression checks")
parser.add_argument("--roboto-italic", type=Path, help="Optional local font for the reported small-cap glyph 442 regression")
args = parser.parse_args()
ide = args.ide.resolve()
if (ide / "Contents").exists():
    ide = ide / "Contents"
java_home = ide / "jbr/Contents/Home"
if not java_home.exists():
    java_home = ide / "jbr"
java = java_home / "bin/java"
kotlin = args.kotlin_home.resolve()
if not java.exists() or not (kotlin / "lib/kotlin-compiler.jar").exists():
    parser.error("Cannot find IDE Java runtime or Kotlin compiler")

build = root / "build/local"
classes = build / "classes"
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(parents=True)
sdk_jars = sorted((ide / "lib").glob("*.jar"))
classpath = os.pathsep.join(map(str, sdk_jars + [kotlin / "lib/kotlin-stdlib.jar"]))
compiler_classpath = os.pathsep.join(map(str, (kotlin / "lib").glob("*.jar")))
sources = sorted((root / "src/main/kotlin").rglob("*.kt"))

# An argument file handles long SDK classpaths and paths containing spaces.
def quote(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'
def compile_sources(sources, destination, compile_classpath, name):
    destination.mkdir(parents=True, exist_ok=True)
    arguments = ["-jvm-target", "21", "-language-version", "2.2", "-api-version", "2.2",
                 "-no-stdlib", "-no-reflect", "-classpath", compile_classpath, "-d", str(destination)] + list(map(str, sources))
    argfile = build / (name + ".args")
    argfile.write_text("\n".join(map(quote, arguments)))
    result = subprocess.run([str(java), "--enable-native-access=ALL-UNNAMED", "-cp", compiler_classpath,
                             "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(argfile)])
    if result.returncode:
        raise SystemExit(result.returncode)

compile_sources(sources, classes, classpath, "compiler")
if args.test:
    test_classes = build / "test-classes"
    compile_sources(sorted((root / "src/test/kotlin").rglob("*.kt")), test_classes,
                    str(classes) + os.pathsep + classpath, "tests")
    test_args = [str(root / "src/test/resources")]
    if args.roboto_italic:
        test_args.append(str(args.roboto_italic.resolve()))
    subprocess.run([str(java), "-Djava.awt.headless=true", "-cp", str(test_classes) + os.pathsep + str(classes) + os.pathsep + classpath,
                    "org.stribor.fontpreview.RegressionChecksKt"] + test_args, check=True)

version = next(line.split("=", 1)[1] for line in (root / "gradle.properties").read_text().splitlines() if line.startswith("pluginVersion="))
jar = build / f"font-preview-{version}.jar"
with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(classes.rglob("*")):
        if file.is_file():
            archive.write(file, file.relative_to(classes))
    resources = root / "src/main/resources"
    for file in sorted(resources.rglob("*")):
        if file.is_file():
            archive.write(file, file.relative_to(resources))
    archive.write(root / "LICENSE", "META-INF/LICENSE")
destination = root / "build/distributions" / f"font-preview-{version}.zip"
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    archive.write(jar, f"font-preview/lib/{jar.name}")
print(f"Built {destination} ({destination.stat().st_size:,} bytes)")
