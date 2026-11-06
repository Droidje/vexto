"""Build the Android-side benchmark postprocessor with an existing JDK and SDK."""
import argparse
import os
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--platform', default='android-36')
    parser.add_argument('--build-tools', default='36.1.0')
    parser.add_argument('--source', type=Path, default=Path(__file__).parent/'android/VextoPostprocess.java')
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    exe = '.exe' if os.name == 'nt' else ''
    javac = args.java_home/'bin'/('javac'+exe)
    java = args.java_home/'bin'/('java'+exe)
    android_jar = args.sdk/'platforms'/args.platform/'android.jar'
    d8 = args.sdk/'build-tools'/args.build_tools/'lib/d8.jar'
    for file in [javac, java, android_jar, d8, args.source]:
        if not file.is_file():
            parser.error(f'Missing required file: {file}')
    classes = args.output_dir/'classes'
    dex = args.output_dir/'dex'
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-classpath', str(android_jar), '-d', str(classes),
                    *map(str, sorted(args.source.parent.glob('*.java')))], check=True)
    class_files = sorted(classes.glob('*.class'))
    subprocess.run([str(java), '-cp', str(d8), 'com.android.tools.r8.D8', '--min-api', '26',
                    '--lib', str(android_jar), '--output', str(dex),
                    *map(str, class_files)], check=True)
    print(dex/'classes.dex')


if __name__ == '__main__':
    main()
