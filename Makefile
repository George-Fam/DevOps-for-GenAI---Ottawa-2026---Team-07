.PHONY: test test-unit package clean docker-build manifest sbom

test:
	mvn test

test-unit:
	mvn test -Dtest="!*SpikeTest,!VerifierTest,!InvestigatorTest,!CheckovAdapterTest"

# `package` also exports the host's warm Maven cache into the Docker build
# context (target/m2-repo). The Dockerfile COPYs it to /root/.m2/repository so
# Trivy can resolve pom.xml dependencies offline inside the action container,
# which has no access to the host runner's ~/.m2 (issue #8). mvn package always
# runs first, so the exported cache contains the current tree's dependencies -
# including any new ones introduced by the PR being scanned.
package:
	mvn package -DskipTests
	mkdir -p target/m2-repo
	if [ -d "$(HOME)/.m2/repository" ]; then \
		cp -r "$(HOME)/.m2/repository/." target/m2-repo/; \
	fi

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
