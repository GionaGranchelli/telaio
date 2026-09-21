package dev.telaio.intellij.k2

import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import dev.telaio.core.impact.*
import dev.telaio.core.symbol.*
import dev.telaio.intellij.bridge.TransactionSymbolRegistry
import java.nio.file.Files

class JetBrainsMultiModuleImpactTest : JavaCodeInsightFixtureTestCase() {

    private lateinit var registry: TransactionSymbolRegistry
    private lateinit var resolver: JetBrainsSymbolResolver
    private lateinit var analyzer: JetBrainsImpactAnalyzer

    override fun setUp() {
        super.setUp()
        registry = TransactionSymbolRegistry()
        resolver = JetBrainsSymbolResolver(project, registry)
        analyzer = JetBrainsImpactAnalyzer(project, registry)
    }

    override fun tearDown() {
        registry.clearAll()
        super.tearDown()
    }

    /**
     * AT-3: Cross-Module Impact
     * Given a library module declaring a class and an app module consuming it,
     * returns affectedModulesCount = 2 with both module names listed in blastRadius.affectedModules.
     */
    fun testAT3CrossModuleImpact() {
        val tempDirNio = Files.createTempDirectory("consumerModule")
        val tempDirVFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempDirNio)
            ?: error("Could not find VFile for temp directory")
        val consumerModule = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "consumerModule", tempDirVFile)
        PsiTestUtil.addSourceRoot(consumerModule, tempDirVFile)
        ModuleRootModificationUtil.addDependency(consumerModule, myFixture.module)

        try {
            val fileA = myFixture.addFileToProject(
                "core/Service.kt",
                """
                package com.example.core

                class Service {
                    fun execute() {}
                }
                """.trimIndent()
            )

            val fileB = com.intellij.openapi.application.WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Throwable> {
                val vfile = tempDirVFile.createChildData(this, "ClientOne.kt")
                com.intellij.openapi.vfs.VfsUtil.saveText(
                    vfile,
                    """
                    package com.example.consumer
                    import com.example.core.Service

                    fun callOne() {
                        Service().execute()
                    }
                    """.trimIndent()
                )
                vfile
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()

            val declOffset = fileA.text.indexOf("execute")
            val resolveRes = resolver.resolveSymbol(
                ResolveSymbolRequest(transactionId = "txn-at3", file = fileA.virtualFile.path, offset = declOffset)
            )
            assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
            val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

            val impactRes = analyzer.analyzeImpact(
                ImpactAnalysisRequest(transactionId = "txn-at3", symbolHandle = handle)
            )

            assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
            val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

            assertEquals(1, report.blastRadius.observedUsageCount)
            assertEquals(2, report.blastRadius.affectedModulesCount)
            assertTrue(report.blastRadius.affectedModules.contains(myFixture.module.name))
            assertTrue(report.blastRadius.affectedModules.contains("consumerModule"))
            assertEquals(1, report.blastRadius.affectedFilesCount)
            assertTrue(report.blastRadius.affectedFiles.contains(fileB.path))
        } finally {
            tempDirNio.toFile().deleteRecursively()
        }
    }

    /**
     * Dedicated Conformance Test: MODULE Scope Resolution
     * Verifies that AnalysisScope.MODULE searches owning module + dependent modules.
     */
    fun testModuleScopeSearch() {
        val tempDirNio = Files.createTempDirectory("consumerMod")
        val tempDirVFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempDirNio)
            ?: error("Could not find VFile for temp directory")
        val consumerModule = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "consumerMod", tempDirVFile)
        PsiTestUtil.addSourceRoot(consumerModule, tempDirVFile)
        ModuleRootModificationUtil.addDependency(consumerModule, myFixture.module)

        try {
            val fileLib = myFixture.addFileToProject(
                "lib/LibService.kt",
                """
                package com.example.lib
                class LibService {
                    fun serve() {}
                }
                """.trimIndent()
            )

            val fileConsumer = com.intellij.openapi.application.WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, Throwable> {
                val vfile = tempDirVFile.createChildData(this, "App.kt")
                com.intellij.openapi.vfs.VfsUtil.saveText(
                    vfile,
                    """
                    package com.example.consumer
                    import com.example.lib.LibService

                    fun consume() {
                        LibService().serve()
                    }
                    """.trimIndent()
                )
                vfile
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()

            val declOffset = fileLib.text.indexOf("serve")
            val resolveRes = resolver.resolveSymbol(
                ResolveSymbolRequest(transactionId = "txn-mod-scope", file = fileLib.virtualFile.path, offset = declOffset)
            )
            assertTrue(resolveRes.outcome is ResolveOutcome.Resolved)
            val handle = (resolveRes.outcome as ResolveOutcome.Resolved).symbol.handle

            val impactRes = analyzer.analyzeImpact(
                ImpactAnalysisRequest(transactionId = "txn-mod-scope", symbolHandle = handle, scope = AnalysisScope.MODULE)
            )

            assertTrue(impactRes.outcome is ImpactOutcome.ImpactReady)
            val report = (impactRes.outcome as ImpactOutcome.ImpactReady).report

            assertEquals(AnalysisScope.MODULE, report.evaluatedScope)
            assertEquals(1, report.blastRadius.observedUsageCount)
            assertEquals(2, report.blastRadius.affectedModulesCount)
            assertTrue(report.blastRadius.affectedModules.contains(myFixture.module.name))
            assertTrue(report.blastRadius.affectedModules.contains("consumerMod"))
        } finally {
            tempDirNio.toFile().deleteRecursively()
        }
    }
}
