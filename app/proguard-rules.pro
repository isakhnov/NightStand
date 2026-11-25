# Nightstand R8 rules. Components declared in the manifest (activities,
# services, receivers, the Application) are kept automatically by AAPT rules.
# Everything else is plain Kotlin with no reflection, so defaults suffice.

# Keep line numbers so crash reports (CrashReporter) stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
