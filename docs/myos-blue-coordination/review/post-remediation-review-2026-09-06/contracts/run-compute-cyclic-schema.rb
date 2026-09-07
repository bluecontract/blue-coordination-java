require 'fileutils'
directory = File.expand_path(__dir__)
worker = '/Users/kamil/Documents/Projects/Blue/myos-simple/durable-library-smoke/build/readiness-evidence/final-r1-r5-seven-20260906-eOCSTM/worker-classpath.txt'
classpath = File.readlines(worker, chomp: true).fetch(1)
output = File.join(directory, 'compiled')
FileUtils.mkdir_p(output)
java = '/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home/bin'
abort('javac failed') unless system(File.join(java, 'javac'), '-cp', classpath, '-d', output, File.join(directory, 'ComputeCyclicSchemaProbe.java'))
exit(system(File.join(java, 'java'), '-Xmx512m', '-cp', output + ':' + classpath, 'blue.coordination.external.ComputeCyclicSchemaProbe') ? 0 : 1)
