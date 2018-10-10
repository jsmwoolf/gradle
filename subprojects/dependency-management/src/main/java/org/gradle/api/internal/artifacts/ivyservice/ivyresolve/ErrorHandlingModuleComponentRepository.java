/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.ivyresolve;

import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ComponentMetadataSupplierDetails;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import org.gradle.api.internal.artifacts.repositories.resolver.MetadataFetchingCost;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.action.InstantiatingAction;
import org.gradle.internal.component.external.model.ModuleDependencyMetadata;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.component.model.ComponentOverrideMetadata;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.component.model.ModuleSource;
import org.gradle.internal.resolve.ArtifactNotFoundException;
import org.gradle.internal.resolve.ArtifactResolveException;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult;
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentArtifactsResolveResult;
import org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import org.gradle.internal.resolve.result.BuildableModuleVersionListingResolveResult;
import org.gradle.internal.resolve.result.ErroringResolveResult;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A ModuleComponentRepository that catches any exception and applies it to the result object.
 * This allows other repository implementations to throw exceptions on failure.
 *
 * This implementation will also blacklist any repository that throws a critical failure, failing-fast with that
 * repository for any subsequent requests.
 */
public class ErrorHandlingModuleComponentRepository implements ModuleComponentRepository {

    private final ModuleComponentRepository delegate;
    private final ErrorHandlingModuleComponentRepositoryAccess local;
    private final ErrorHandlingModuleComponentRepositoryAccess remote;

    public ErrorHandlingModuleComponentRepository(ModuleComponentRepository delegate, RepositoryBlacklister remoteRepositoryBlacklister) {
        this.delegate = delegate;
        local = new ErrorHandlingModuleComponentRepositoryAccess(delegate.getLocalAccess(), getId(), RepositoryBlacklister.NoOpBlacklister.INSTANCE);
        remote = new ErrorHandlingModuleComponentRepositoryAccess(delegate.getRemoteAccess(), getId(), remoteRepositoryBlacklister);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public ModuleComponentRepositoryAccess getLocalAccess() {
        return local;
    }

    @Override
    public ModuleComponentRepositoryAccess getRemoteAccess() {
        return remote;
    }

    @Override
    public Map<ComponentArtifactIdentifier, ResolvableArtifact> getArtifactCache() {
        return delegate.getArtifactCache();
    }

    @Override
    public InstantiatingAction<ComponentMetadataSupplierDetails> getComponentMetadataSupplier() {
        return delegate.getComponentMetadataSupplier();
    }

    private static final class ErrorHandlingModuleComponentRepositoryAccess implements ModuleComponentRepositoryAccess {
        private static final Logger LOGGER = Logging.getLogger(ErrorHandlingModuleComponentRepositoryAccess.class);
        private final static String MAX_RETRIES_BEFORE_BLACKLISTING = "org.gradle.internal.repository.max.retries";
        private final static String INITIAL_BACKOFF_MS = "org.gradle.internal.repository.initial.backoff";

        private final static String BLACKLISTED_REPOSITORY_ERROR_MESSAGE = "Skipped due to earlier error";

        private final ModuleComponentRepositoryAccess delegate;
        private final String repositoryId;
        private final RepositoryBlacklister repositoryBlacklister;
        private final int maxRetriesCount;
        private final int initialBackOff;

        private ErrorHandlingModuleComponentRepositoryAccess(ModuleComponentRepositoryAccess delegate, String repositoryId, RepositoryBlacklister repositoryBlacklister) {
            this(delegate, repositoryId, repositoryBlacklister, Integer.getInteger(MAX_RETRIES_BEFORE_BLACKLISTING, 3), Integer.getInteger(INITIAL_BACKOFF_MS, 125));
        }

        private ErrorHandlingModuleComponentRepositoryAccess(ModuleComponentRepositoryAccess delegate, String repositoryId, RepositoryBlacklister repositoryBlacklister, int maxRetriesCount, int initialBackoff) {
            assert maxRetriesCount > 0 : "Max retries must be > 0";
            assert initialBackoff >= 0 : "Initial backoff must be >= 0";
            this.delegate = delegate;
            this.repositoryId = repositoryId;
            this.repositoryBlacklister = repositoryBlacklister;
            this.maxRetriesCount = maxRetriesCount;
            this.initialBackOff = initialBackoff;
        }

        @Override
        public String toString() {
            return "error handling > " + delegate.toString();
        }

        @Override
        public void listModuleVersions(ModuleDependencyMetadata dependency, BuildableModuleVersionListingResolveResult result) {
            performOperationWithRetries(result, () -> delegate.listModuleVersions(dependency, result), throwable -> {
                if (throwable == null) {
                    return new ModuleVersionResolveException(dependency.getSelector(), BLACKLISTED_REPOSITORY_ERROR_MESSAGE);
                }
                ModuleComponentSelector selector = dependency.getSelector();
                String message = "Failed to list versions for " + selector.getGroup() + ":" + selector.getModule() + ".";
                return new ModuleVersionResolveException(selector, message, throwable);
            });
        }

        @Override
        public void resolveComponentMetaData(ModuleComponentIdentifier moduleComponentIdentifier, ComponentOverrideMetadata requestMetaData, BuildableModuleComponentMetaDataResolveResult result) {
            performOperationWithRetries(result, () -> delegate.resolveComponentMetaData(moduleComponentIdentifier, requestMetaData, result), throwable -> {
                if (throwable == null) {
                    return new ModuleVersionResolveException(moduleComponentIdentifier, BLACKLISTED_REPOSITORY_ERROR_MESSAGE);
                }
                return new ModuleVersionResolveException(moduleComponentIdentifier, throwable);
            });
        }

        @Override
        public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
            performOperationWithRetries(result, () -> delegate.resolveArtifactsWithType(component, artifactType, result), throwable -> {
                if (throwable == null) {
                    return new ArtifactResolveException(component.getId(), BLACKLISTED_REPOSITORY_ERROR_MESSAGE);
                }
                return new ArtifactResolveException(component.getId(), throwable);
            });
        }

        @Override
        public void resolveArtifacts(ComponentResolveMetadata component, BuildableComponentArtifactsResolveResult result) {
            performOperationWithRetries(result, () -> delegate.resolveArtifacts(component, result), throwable -> {
                if (throwable == null) {
                    return new ArtifactResolveException(component.getId(), BLACKLISTED_REPOSITORY_ERROR_MESSAGE);
                }
                return new ArtifactResolveException(component.getId(), throwable);
            });
        }

        @Override
        public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSource moduleSource, BuildableArtifactResolveResult result) {
            performOperationWithRetries(result, () -> {
                delegate.resolveArtifact(artifact, moduleSource, result);
                if (result.hasResult()) {
                    ArtifactResolveException failure = result.getFailure();
                    if (!(failure instanceof ArtifactNotFoundException)) {
                        return failure;
                    }
                }
                return null;
            }, throwable -> {
                if (throwable == null) {
                    return new ArtifactResolveException(artifact.getId(), BLACKLISTED_REPOSITORY_ERROR_MESSAGE);
                }
                return new ArtifactResolveException(artifact.getId(), throwable);
            });
        }

        private <E extends Throwable, R extends ErroringResolveResult<E>> void performOperationWithRetries(R result, Callable<E> operation, Transformer<E, Throwable> onError) {
            if (repositoryBlacklister.isBlacklisted(repositoryId)) {
                result.failed(onError.transform(null));
                return;
            }

            tryResolveAndMaybeBlacklist(result, operation, onError);
        }

        private <E extends Throwable, R extends ErroringResolveResult<E>> void performOperationWithRetries(R result, Runnable operation, Transformer<E, Throwable> onError) {
            if (repositoryBlacklister.isBlacklisted(repositoryId)) {
                result.failed(onError.transform(null));
                return;
            }

            tryResolveAndMaybeBlacklist(result, operation, onError);
        }

        private <E extends Throwable, R extends ErroringResolveResult<E>> void tryResolveAndMaybeBlacklist(R result, Runnable operation, Transformer<E, Throwable> onError) {
            tryResolveAndMaybeBlacklist(result, () -> {
                operation.run();
                return null;
            }, onError);
        }

        private <E extends Throwable, R extends ErroringResolveResult<E>> void tryResolveAndMaybeBlacklist(R result, Callable<E> operation, Transformer<E, Throwable> onError) {
            int retries = 0;
            int backoff = initialBackOff;

            while (retries < maxRetriesCount) {
                retries++;
                E failure;
                Throwable unexpectedFailure = null;
                try {
                    failure = operation.call();
                    if (failure == null) {
                        if (retries > 1) {
                            LOGGER.debug("Successfully fetched external resource after {} retries", retries - 1);
                        }
                        return;
                    }
                } catch (Throwable throwable) {
                    unexpectedFailure = throwable;
                    failure = onError.transform(throwable);
                }
                if (retries == maxRetriesCount) {
                    if (unexpectedFailure != null) {
                        repositoryBlacklister.blacklistRepository(repositoryId, unexpectedFailure);
                    }
                    result.failed(failure);
                    break;
                } else {
                    LOGGER.debug("Error while accessing remote repository {}. Waiting {}ms before next retry. {} retries left", repositoryId, backoff, maxRetriesCount - retries);
                    try {
                        Thread.sleep(backoff);
                        backoff *= 2;
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }

        @Override
        public MetadataFetchingCost estimateMetadataFetchingCost(ModuleComponentIdentifier moduleComponentIdentifier) {
            return delegate.estimateMetadataFetchingCost(moduleComponentIdentifier);
        }
    }
}
