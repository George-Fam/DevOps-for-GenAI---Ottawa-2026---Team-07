.PHONY: test test-unit package clean

test:
	mvn test

test-unit:
	mvn test -Dtest="!*SpikeTest,!VerifierTest,!InvestigatorTest,!CheckovAdapterTest"

package:
	mvn package

clean:
	mvn clean
