/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.dependencies.maven.impl

import com.google.inject.Guice
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.apache.maven.settings.Settings
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.collection.CollectResult
import org.eclipse.aether.collection.DependencyCollectionException
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyFilter
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.Proxy
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
import org.eclipse.aether.resolution.ArtifactResult
import org.eclipse.aether.util.filter.DependencyFilterUtils
import org.eclipse.aether.util.graph.visitor.FilteringDependencyVisitor
import org.eclipse.aether.util.graph.visitor.TreeDependencyVisitor
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.util.repository.DefaultMirrorSelector
import org.eclipse.aether.util.repository.DefaultProxySelector
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.asSuccess

val mavenCentral: RemoteRepository = RemoteRepository.Builder("maven central", "default", "https://repo.maven.apache.org/maven2/").build()

internal enum class ResolutionKind {
    NON_TRANSITIVE,
    TRANSITIVE,

    // Partial resolution is successful in case if dependency tree was built,
    // but may return non-complete list of dependencies - i.e. while requesting sources, some libraries may lack sources artifacts.
    // Resolution errors will be attached as reports.
    // Also, might be slightly slower than usual transitive resolution.
    TRANSITIVE_PARTIAL
}

internal class AetherResolveSession(
    localRepoDirectory: File? = null,
    remoteRepos: List<RemoteRepository> = listOf(mavenCentral)
) {

    private val localRepoPath by lazy {
        localRepoDirectory?.absolutePath ?: settings.localRepository
    }

    private val remotes by lazy {
        val proxySelector = settings.activeProxy?.let { proxy ->
            val selector = DefaultProxySelector()
            val auth = with(AuthenticationBuilder()) {
                addUsername(proxy.username)
                addPassword(proxy.password)
                build()
            }
            selector.add(
                Proxy(
                    proxy.protocol,
                    proxy.host,
                    proxy.port,
                    auth
                ), proxy.nonProxyHosts
            )
            selector
        }
        val mirrorSelector = getMirrorSelector()
        remoteRepos.mapNotNull {
            val builder = RemoteRepository.Builder(it)
            if (proxySelector != null) {
                builder.setProxy(proxySelector.getProxy(builder.build()))
            }
            val built = builder.build()
            if (!built.protocol.matches(Regex("https?|file"))) {
                //Logger.warn(
                //        this,
                //        "%s ignored (only S3, HTTP/S, and FILE are supported)",
                //        repo
                //);
                null
            } else {
                mirrorSelector.getMirror(built) ?: built
            }
        }
    }

    private val injector by lazy {
        Guice.createInjector(MavenResolverModule())
    }

    private val repositorySystem by lazy { injector.getInstance(RepositorySystem::class.java) }

    private val repositorySystemSession: RepositorySystemSession by lazy {
        val localRepo = LocalRepository(localRepoPath)
        MavenRepositorySystemUtils.newSession().also {
            it.localRepositoryManager = repositorySystem.newLocalRepositoryManager(it, localRepo)
        }
    }

    fun resolve(
        root: Artifact,
        scope: String,
        kind: ResolutionKind,
        filter: DependencyFilter?,
        classifier: String? = null,
        extension: String? = null,
    ): ResultWithDiagnostics<List<File>> {
        if (kind == ResolutionKind.NON_TRANSITIVE) return resolveArtifact(root).asSuccess()

        val requests = resolveTree(root, scope, filter, classifier, extension)

        @Suppress("KotlinConstantConditions")
        return when (kind) {
            ResolutionKind.TRANSITIVE -> resolveDependencies(requests) {
                repositorySystem.resolveArtifacts(
                    repositorySystemSession,
                    requests
                ).toFiles().asSuccess()
            }

            ResolutionKind.TRANSITIVE_PARTIAL -> resolveDependencies(requests) {
                val reports = mutableListOf<ScriptDiagnostic>()
                val results = mutableListOf<File>()
                for (req in requests) {
                    try {
                        results.add(
                            repositorySystem.resolveArtifact(
                                repositorySystemSession,
                                req
                            ).artifact.file
                        )
                    } catch (e: ArtifactResolutionException) {
                        reports.add(
                            ScriptDiagnostic(
                                ScriptDiagnostic.unspecifiedError,
                                e.message.orEmpty(),
                                ScriptDiagnostic.Severity.WARNING,
                                exception = e
                            )
                        )
                    }
                }

                ResultWithDiagnostics.Success(results, reports)
            }

            ResolutionKind.NON_TRANSITIVE -> {
                error("This statement is not reachable")
            }
        }
    }

    private fun resolveTree(
        root: Artifact,
        scope: String,
        filter: DependencyFilter?,
        classifier: String?,
        extension: String?,
    ): Collection<ArtifactRequest> {
        return fetch(
            request(Dependency(root, scope)),
            { req ->
                val requestsBuilder = ArtifactRequestBuilder(classifier, extension)
                val collectionResult = repositorySystem.collectDependencies(repositorySystemSession, req)
                collectionResult.root.accept(
                    TreeDependencyVisitor(
                        FilteringDependencyVisitor(
                            requestsBuilder,
                            filter ?: DependencyFilterUtils.classpathFilter(scope)
                        )
                    )
                )

                requestsBuilder.requests
            },
            { req, ex ->
                DependencyCollectionException(
                    CollectResult(req),
                    ex.message,
                    ex
                )
            }
        )
    }

    private fun Collection<ArtifactResult>.toFiles() = map { it.artifact.file }

    private fun resolveDependencies(
        requests: Collection<ArtifactRequest>,
        resolveAction: (Collection<ArtifactRequest>) -> ResultWithDiagnostics<List<File>>
    ): ResultWithDiagnostics<List<File>> {
        return fetch(
            requests,
            resolveAction
        ) { _, ex ->
            DependencyCollectionException(
                null,
                ex.message,
                ex
            )
        }
    }

    private fun resolveArtifact(artifact: Artifact): List<File> {
        val request = ArtifactRequest()
        request.artifact = artifact
        for (repo in remotes) {
            request.addRepository(repo)
        }

        return fetch(
            request,
            { req -> listOf(repositorySystem.resolveArtifact(repositorySystemSession, req)) },
            { req, ex -> ArtifactResolutionException(listOf(ArtifactResult(req)), ex.message, IllegalArgumentException(ex)) }
        ).toFiles()
    }

    private fun request(root: Dependency): CollectRequest {
        val request = CollectRequest()
        request.root = root
        for (repo in remotes) {
            request.addRepository(repo)
        }
        return request
    }

    private fun <RequestT, ResultT> fetch(
        request: RequestT,
        fetchBody: (RequestT) -> ResultT,
        wrapException: (RequestT, Exception) -> Exception
    ): ResultT {
        return try {
            synchronized(this) {
                fetchBody(request)
            }
            // @checkstyle IllegalCatch (1 line)
        } catch (ex: Exception) {
            throw wrapException(request, ex)
        }
    }

    private fun getMirrorSelector(): DefaultMirrorSelector {
        val selector = DefaultMirrorSelector()
        val mirrors = settings.mirrors
        if (mirrors != null) {
            for (mirror in mirrors) {
                selector.add(
                    mirror.id, mirror.url, mirror.layout, false, false,
                    mirror.mirrorOf, mirror.mirrorOfLayouts
                )
            }
        }
        return selector
    }

    private val settings: Settings by lazy {
        createMavenSettings()
    }
}