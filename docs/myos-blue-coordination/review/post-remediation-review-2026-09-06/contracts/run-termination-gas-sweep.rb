require 'fileutils'
directory = File.expand_path(__dir__)
language = '/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java'
worker = '/Users/kamil/Documents/Projects/Blue/myos-simple/durable-library-smoke/build/readiness-evidence/final-r1-r5-seven-20260906-eOCSTM/worker-classpath.txt'
classpath = [File.join(language, 'blue-contracts-core/build/classes/java/test'),
             File.join(language, 'blue-contracts-core/build/resources/test'), File.readlines(worker, chomp: true).fetch(1)].join(':')
output = File.join(directory, 'compiled')
FileUtils.mkdir_p(output)
java = '/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home/bin'
abort('javac failed') unless system(File.join(java, 'javac'), '-cp', classpath, '-d', output, File.join(directory, 'TerminationGasSweep.java'))
exit(system(File.join(java, 'java'), '-Xmx1g', '-cp', output + ':' + classpath,
            'blue.language.processor.closure.TerminationGasSweep') ? 0 : 1)
