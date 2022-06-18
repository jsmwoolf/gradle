/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.fixtures.executer


import org.gradle.test.fixtures.file.TestFile
import org.gradle.util.GradleVersion

/**
 * Commit distribution is a distribution built from a commit.
 * Its version looks like "7.5-commit-1a2b3c".
 *
 * The commit distributions are generated at the following location:
 *
 * +-- intTestHomeDir
 *    +-- commit-distributions
 *        +-- gradle-7.5-commit-1a2b3c4.zip
 *        +-- gralde-7.5-commit-1a2b3c4
 *            +-- bin
 *            +-- lib
 *            +-- ..
 *        +-- gradle-tooling-api-7.5-commit-1a2b3c4.jar
 */
class CommitDistribution extends DefaultGradleDistribution {
    private final TestFile commitDistributionsDir;

    CommitDistribution(String version, TestFile commitDistributionsDir) {
        super(GradleVersion.version(version), commitDistributionsDir.file(version), commitDistributionsDir.file("gradle-${version}.zip"))
        this.commitDistributionsDir = commitDistributionsDir;
    }

    TestFile getGradleHomeDir() {
        TestFile gradleHome = super.getGradleHomeDir()
        if (!gradleHome.isDirectory()) {
            super.binDistribution.usingNativeTools().unzipTo(gradleHome)
        }
        return gradleHome
    }

    static boolean isCommitDistribution(String version) {
        return version.contains("-commit-")
    }

    static File getToolingApiJar(String version) {
        return IntegrationTestBuildContext.INSTANCE.getGradleUserHomeDir().parentFile.file("commit-distributions/gradle-tooling-api-${version}.jar")
    }
}
