interface A {
    @JsName("foo") fun f()
}

interface B {
    @JsName("foo") fun g()
}

class C : A, B {
    <!JS_NAME_CLASH!>override fun f()<!> {}

    <!JS_NAME_CLASH!>override fun g()<!> {}
}

abstract class D : A, B

open class E {
    open fun f() {}

    open fun g() {}
}

class F : E(), A, B
