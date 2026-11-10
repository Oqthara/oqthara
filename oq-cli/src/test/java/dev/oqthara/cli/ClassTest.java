package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classes and contracts (ADR-0027). Every assertion here is a behaviour the ADR promises,
 * so a change to one of them is a change to the documented model rather than a detail.
 */
class ClassTest {
    private String run(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    private String failure(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(1, result.code(), "expected a program error, got: " + result.stdout());
        return result.stderr();
    }

    @Test
    void constructsAndPrintsInstances() {
        assertEquals("3\n", run("""
                class Counter(value)
                end
                print Counter(3).value
                """));
    }

    @Test
    void typeNameIsTheClassName() {
        assertEquals("Counter\n", run("""
                class Counter(value)
                end
                print type_of(Counter(1))
                """));
    }

    @Test
    void reprNamesTheObjectRatherThanItsFields() {
        assertEquals("<Counter object>\n", run("""
                class Counter(value = 1)
                end
                print Counter()
                """));
    }

    @Test
    void omittedFieldsUseTheirDefaults() {
        assertEquals("0\n", run("""
                class Counter(value = 0, step = 2)
                end
                c = Counter()
                print c.value
                """));
    }

    @Test
    void defaultsArePerConstruction() {
        // A mutable default must not be shared, exactly as `data` guarantees.
        assertEquals("1,0\n", run("""
                class Bag(items = [])
                  fun add(item)
                    push(this.items, item)
                  end
                end
                a = Bag()
                b = Bag()
                a.add(1)
                print "{length(a.items)},{length(b.items)}"
                """));
    }

    @Test
    void methodsReadAndWriteFieldsThroughThis() {
        assertEquals("1\n2\n", run("""
                class Counter(value = 0)
                  fun bump()
                    this.value = this.value + 1
                    return this.value
                  end
                end
                c = Counter()
                print c.bump()
                print c.bump()
                """));
    }

    @Test
    void methodsTakeArgumentsAndCallEachOther() {
        assertEquals("12\n", run("""
                class Box(value)
                  fun double()
                    return this.value * 2
                  end
                  fun plus(n)
                    return this.double() + n
                  end
                end
                print Box(5).plus(2)
                """));
    }

    @Test
    void initRunsAfterFieldsAreFilled() {
        assertEquals("20\n", run("""
                class Scaled(value)
                  init
                    this.value = this.value * 10
                  end
                end
                print Scaled(2).value
                """));
    }

    @Test
    void initSeesEveryDeclaredField() {
        // Every field is filled before `init` runs, so an initializer can read any of them,
        // including one that a later argument supplied.
        assertEquals("3\n", run("""
                class Sum(a, b, total = 0)
                  init
                    this.total = this.a + this.b
                  end
                end
                print Sum(1, 2).total
                """));
    }

    @Test
    void initCannotInventAField() {
        String message = failure("""
                class Sum(a, b)
                  init
                    this.total = this.a + this.b
                  end
                end
                Sum(1, 2)
                """);
        assertTrue(message.contains("no field named 'total'"), message);
    }

    @Test
    void failedInitDoesNotLeaveAnInstance() {
        String message = failure("""
                class Guard(v)
                  init
                    if this.v < 0
                      error("negative")
                    end
                  end
                end
                Guard(-1)
                """);
        assertTrue(message.contains("negative"), message);
    }

    @Test
    void equalityIsIdentityNotStructure() {
        assertEquals("false\ntrue\n", run("""
                class Point(x)
                end
                a = Point(1)
                b = Point(1)
                print a == b
                print a == a
                """));
    }

    @Test
    void instancesAreNotDestructurable() {
        assertTrue(failure("""
                class Point(x, y)
                end
                (x, y) = Point(1, 2)
                """).length() > 0);
    }

    @Test
    void unknownFieldNamesTheAvailableOnes() {
        String message = failure("""
                class Point(x, y)
                end
                p = Point(1, 2)
                print p.z
                """);
        assertTrue(message.contains("no field named 'z'"), message);
        assertTrue(message.contains("x, y"), message);
    }

    @Test
    void unknownMethodIsReportedOnTheClass() {
        String message = failure("""
                class Point(x)
                end
                Point(1).norm()
                """);
        assertTrue(message.contains("type Point has no method 'norm'"), message);
    }

    @Test
    void wrongArityNamesTheArityThatExists() {
        String message = failure("""
                class Counter(value)
                  fun add(n)
                    return this.value + n
                  end
                end
                Counter(1).add(1, 2)
                """);
        assertTrue(message.contains("does not take 2 arguments"), message);
        assertTrue(message.contains("It takes 1"), message);
    }

    @Test
    void privateFieldsAreRejectedOutsideTheirClass() {
        String message = failure("""
                class Account(_balance = 0)
                end
                a = Account()
                print a._balance
                """);
        assertTrue(message.contains("is private"), message);
    }

    @Test
    void privateFieldsRemainReachableInsideTheirClass() {
        assertEquals("7\n", run("""
                class Account(_balance = 0)
                  fun deposit(n)
                    this._balance = this._balance + n
                    return this._balance
                  end
                end
                print Account().deposit(7)
                """));
    }

    @Test
    void privateFieldsAreNotReachableFromAnotherClass() {
        String message = failure("""
                class A(_v = 1)
                  fun peek(other)
                    return other._v
                  end
                end
                class B(_v = 2)
                end
                A().peek(B())
                """);
        assertTrue(message.contains("private"), message);
    }

    @Test
    void aClassCannotShadowABuiltinMethod() {
        // ADR-0027 D3: class dispatch runs after MethodResolver, so `push` stays the list one.
        assertEquals("3\n", run("""
                class Weird()
                  fun push(x)
                    return 99
                  end
                end
                xs = [1]
                push(xs, 2)
                xs.push(3)
                print length(xs)
                """));
    }

    @Test
    void instanceMethodsDoNotAffectOtherValues() {
        assertEquals("3\n", run("""
                class Counter(value)
                  fun size()
                    return this.value
                  end
                end
                print length([1, 2, 3])
                """));
    }

    @Test
    void contractIsSatisfiedByDeclaringTheMethods() {
        assertEquals("16\n", run("""
                contract Shape
                  fun area()
                end
                class Square(side) implements Shape
                  fun area()
                    return this.side * this.side
                  end
                end
                print Square(4).area()
                """));
    }

    @Test
    void missingContractMethodFailsAtTheDeclaration() {
        String message = failure("""
                contract Shape
                  fun area()
                end
                class Empty(side) implements Shape
                end
                """);
        assertTrue(message.contains("implements Shape but has no 'area'"), message);
    }

    @Test
    void unknownContractNameFails() {
        String message = failure("""
                class Square(side) implements Missing
                end
                """);
        assertTrue(message.contains("which is not declared"), message);
    }

    @Test
    void duplicatedContractSignatureFails() {
        String message = failure("""
                contract Shape
                  fun area()
                  fun area(a)
                end
                """);
        assertTrue(message.contains("more than once"), message);
    }

    @Test
    void fieldAndMethodMayNotShareAName() {
        String message = failure("""
                class Clash(v)
                  fun v()
                    return 1
                  end
                end
                """);
        assertTrue(message.contains("both a field and a method named 'v'"), message);
    }

    @Test
    void duplicateMethodNamesAreRejected() {
        String message = failure("""
                class Twice()
                  fun m()
                    return 1
                  end
                  fun m()
                    return 2
                  end
                end
                """);
        assertTrue(message.contains("declares 'm' more than once"), message);
    }

    @Test
    void duplicateFieldNamesAreRejected() {
        String message = failure("""
                class Twice(x, x)
                end
                """);
        assertTrue(message.contains("declares field 'x' more than once"), message);
    }

    @Test
    void contractsCannotBeConstructed() {
        String message = failure("""
                contract Shape
                  fun area()
                end
                Shape()
                """);
        assertTrue(message.contains("cannot be constructed"), message);
    }

    @Test
    void classAndThisRemainUsableAsOrdinaryNames() {
        // ADR-0027 D1: both are contextual, so existing programs keep working.
        assertEquals("5\n1\n", run("""
                this = 5
                class = 1
                print this
                print class
                """));
    }

    @Test
    void dataBehaviourIsUnchanged() {
        assertEquals("Point(x: 1, y: 2)\ntrue\n", run("""
                data Point(x, y)
                print Point(1, 2)
                print Point(1, 2) == Point(1, 2)
                """));
    }

    @Test
    void tooManyConstructorArgumentsNamesTheFields() {
        String message = failure("""
                class Point(x, y)
                end
                Point(1, 2, 3)
                """);
        assertTrue(message.contains("expects at most 2 arguments"), message);
        assertTrue(message.contains("x, y"), message);
    }

    @Test
    void missingConstructorArgumentSuggestsADefault() {
        String message = failure("""
                class Point(x, y)
                end
                Point(1)
                """);
        assertTrue(message.contains("missing value for field 'y'"), message);
        assertTrue(message.contains("default"), message);
    }

    @Test
    void thisCannotBeReassigned() {
        String message = failure("""
                class C(v)
                  fun reset()
                    this = 1
                  end
                end
                C(1).reset()
                """);
        assertTrue(message.contains("cannot"), message);
    }
}
