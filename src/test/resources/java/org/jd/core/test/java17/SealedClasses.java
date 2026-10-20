package org.jd.core.test.java17;

/**
 * Java 17 (JEP 409): sealed classes and interfaces. Class file has a PermittedSubclasses
 * attribute; permits may be explicit or inferred from the same compilation unit.
 */
public class SealedClasses {
    public sealed interface Shape permits Circle, Square, Rectangle {
    }

    public record Circle(double radius) implements Shape {
    }

    public record Square(double side) implements Shape {
    }

    public static final class Rectangle implements Shape {
        final double width;
        final double height;

        public Rectangle(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }

    public static abstract sealed class Vehicle permits Car, Truck, Bike {
    }

    public static final class Car extends Vehicle {
    }

    public static non-sealed class Truck extends Vehicle {
    }

    public static final class Bike extends Vehicle {
    }

    public static class BigTruck extends Truck {
    }

    public sealed interface Expr {
        record Num(int value) implements Expr {
        }

        record Add(Expr left, Expr right) implements Expr {
        }

        record Mul(Expr left, Expr right) implements Expr {
        }
    }

    public double area(Shape shape) {
        if (shape instanceof Circle c) {
            return Math.PI * c.radius() * c.radius();
        } else if (shape instanceof Square s) {
            return s.side() * s.side();
        } else if (shape instanceof Rectangle r) {
            return r.width * r.height;
        }
        throw new IllegalArgumentException();
    }

    public int eval(Expr expr) {
        if (expr instanceof Expr.Num n) {
            return n.value();
        } else if (expr instanceof Expr.Add a) {
            return eval(a.left()) + eval(a.right());
        } else if (expr instanceof Expr.Mul m) {
            return eval(m.left()) * eval(m.right());
        }
        throw new IllegalArgumentException();
    }
}
