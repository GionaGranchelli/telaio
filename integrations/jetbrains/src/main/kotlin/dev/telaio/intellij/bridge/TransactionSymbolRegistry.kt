package dev.telaio.intellij.bridge

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.telaio.core.symbol.SymbolHandle
import dev.telaio.core.symbol.SymbolRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ResolvedSymbolEntry(
    val handle: SymbolHandle,
    val symbolRef: SymbolRef,
    val psiPointer: SmartPsiElementPointer<PsiElement>,
    val qualifiedIdentity: String
)

class TransactionSymbolRegistry {
    private val counter = AtomicLong(1000)
    // Map of transactionId -> (handleValue -> ResolvedSymbolEntry)
    private val transactions = ConcurrentHashMap<String, ConcurrentHashMap<String, ResolvedSymbolEntry>>()

    fun register(
        project: Project,
        transactionId: String,
        element: PsiElement,
        symbolRefFactory: (SymbolHandle) -> SymbolRef
    ): ResolvedSymbolEntry {
        val txnMap = transactions.computeIfAbsent(transactionId) { ConcurrentHashMap() }
        
        // Generate a new transaction-scoped opaque handle
        val id = counter.incrementAndGet().toString(16)
        val handle = SymbolHandle.create(transactionId, id)
        val symbolRef = symbolRefFactory(handle)
        
        val smartPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        val entry = ResolvedSymbolEntry(
            handle = handle,
            symbolRef = symbolRef,
            psiPointer = smartPointer,
            qualifiedIdentity = symbolRef.qualifiedIdentity
        )
        
        txnMap[handle.value] = entry
        return entry
    }

    fun get(handle: SymbolHandle): ResolvedSymbolEntry? {
        val txnMap = transactions[handle.transactionId] ?: return null
        return txnMap[handle.value]
    }

    fun clearTransaction(transactionId: String) {
        transactions.remove(transactionId)
    }

    fun clearAll() {
        transactions.clear()
    }
}
