package dev.telaio.core.symbol

interface SemanticSymbolResolver {
    fun resolveSymbol(request: ResolveSymbolRequest): ResolveSymbolResult
}
