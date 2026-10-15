import io.github.lazily.*

private data class ConsumerSchema(val label: String)

fun main() {
    val ctx = Context()
    val source = ctx.source(1)
    val computed = ctx.computed { get(source) * 2 }
    var runs = 0
    val scope = ctx.scope()
    scope.effect {
        check(get(computed) >= 2) { "computed value was not observable" }
        runs++
        null
    }
    ctx.batch {
        source.set(this, 2)
        source.set(this, 3)
    }
    check(ctx.get(computed) == 6 && runs == 2) {
        "batch result = ${ctx.get(computed)}, runs = $runs"
    }
    scope.end()
    source.set(ctx, 4)
    check(runs == 2) { "closed scope still observed updates" }
    val typed = ctx.source(ConsumerSchema("typed"))
    check(ctx.get(typed).label == "typed") { "typed context value was not preserved" }
    println("lazily-kt artifact consumer: OK")
}
