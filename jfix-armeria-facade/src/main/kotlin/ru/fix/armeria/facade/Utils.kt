package ru.fix.armeria.facade

internal sealed class Either<out A, out B> {
    class Left<A>(val value: A) : Either<A, Nothing>()
    class Right<B>(val value: B) : Either<Nothing, B>()

    val leftOrNull: A?
        get() = if (this is Left) this.value else null

    val rightOrNull: B?
        get() = if (this is Right) this.value else null
}