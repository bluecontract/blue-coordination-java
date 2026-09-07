require 'fileutils'
directory = File.expand_path(__dir__)
bex = '/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-bex-java'
worker = '/Users/kamil/Documents/Projects/Blue/myos-simple/durable-library-smoke/build/readiness-evidence/final-r1-r5-seven-20260906-eOCSTM/worker-classpath.txt'
classpath = [File.join(bex, 'blue-bex-conformance/build/classes/java/test'),
             File.join(bex, 'blue-bex-conformance/build/resources/test'), File.readlines(worker, chomp: true).fetch(1)].join(':')
output = File.join(directory, 'compiled')
FileUtils.mkdir_p(output)
java = '/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home/bin'
abort('javac failed') unless system(File.join(java, 'javac'), '-cp', classpath, '-d', output, File.join(directory, 'BexCyclicSchemaProbe.java'))
exit(system(File.join(java, 'java'), '-Xmx512m', '-cp', output + ':' + classpath, 'blue.bex.BexCyclicSchemaProbe') ? 0 : 1)
