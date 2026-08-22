.PHONY: test test-unit package clean docker-build manifest sbom

test:
	mvn test

test-unit:
	mvn test -Dtest="!*SpikeTest,!VerifierTest,!InvestigatorTest,!CheckovAdapterTest"

package:
	mvn package -DskipTests

clean:
	mvn clean
	-docker rmi yami:test 2>/dev/null || true

docker-build: package
	docker build -t yami:test .

manifest: package
	java -cp target/yami.jar com.yami.governance.GovManifestTool generate .
	java -cp target/yami.jar com.yami.governance.GovManifestTool verify .

sbom:
	trivy fs --format spdx-json -o sbom.json .
