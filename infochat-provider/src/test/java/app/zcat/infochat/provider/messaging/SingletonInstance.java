package app.zcat.infochat.provider.messaging;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal {@link Instance} backed by a fixed list. The router only
 * iterates the instance; the other CDI accessors are unused and
 * throw {@link UnsupportedOperationException} to fail loudly if a
 * future change starts consuming them.
 */
final class SingletonInstance<T> implements Instance<T> {
    private final List<T> items;

    @SafeVarargs
    SingletonInstance(T... items) {
        this.items = List.of(items);
    }

    @Override
    public Iterator<T> iterator() {
        return items.iterator();
    }

    @Override
    public T get() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUnsatisfied() {
        return items.isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
        return items.size() > 1;
    }

    @Override
    public void destroy(T instance) {
        // no-op
    }

    @Override
    public Handle<T> getHandle() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        throw new UnsupportedOperationException();
    }
}
