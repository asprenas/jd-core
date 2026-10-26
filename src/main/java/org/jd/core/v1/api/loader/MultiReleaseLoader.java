/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.api.loader;

/**
 * Loader of a multi-release jar (JEP 238): a class is read from the highest 'META-INF/versions/k/' directory with
 * 'k' lower than or equal to the target version, or from the root when no versioned class exists. The choice is
 * made for each class, as the JVM does.
 * <p>
 * Checking the 'Multi-Release: true' attribute of the manifest is the responsibility of the caller: without it, the
 * delegate loader should be used directly.
 */
public class MultiReleaseLoader implements Loader {
    protected static final String VERSIONS_PREFIX = "META-INF/versions/";
    /** The versioned directories are ignored before Java 9 */
    protected static final int FIRST_VERSION = 9;

    protected final Loader delegate;
    protected final int targetVersion;

    /**
     * @param delegate the loader of the jar entries, the names are relative to the root of the jar
     * @param targetVersion the Java feature version of the target runtime (e.g. 8, 11, 17)
     */
    public MultiReleaseLoader(Loader delegate, int targetVersion) {
        this.delegate = delegate;
        this.targetVersion = targetVersion;
    }

    public Loader getDelegate() { return delegate; }

    public int getTargetVersion() { return targetVersion; }

    @Override
    public boolean canLoad(String internalName) {
        return resolve(internalName) != null;
    }

    @Override
    public byte[] load(String internalName) throws LoaderException {
        String name = resolve(internalName);
        return (name == null) ? null : delegate.load(name);
    }

    /** @return the name to load from the delegate, or null when the class is not found */
    protected String resolve(String internalName) {
        for (int version = targetVersion; version >= FIRST_VERSION; version--) {
            String name = VERSIONS_PREFIX + version + '/' + internalName;

            if (delegate.canLoad(name)) {
                return name;
            }
        }

        return delegate.canLoad(internalName) ? internalName : null;
    }
}
