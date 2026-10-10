# The C++ entry points resolve the fully qualified Java class and method names.
# Keep this small JNI surface stable in both the public and tablet preview builds.
-keep class fr.androforge.NativeBridge { *; }
# Keep the public storage adapter inspectable by the signed Android validation
# harness, including its byte-exact backup records and failure reports.
-keep,allowoptimization class fr.androforge.DroidSdStorage { public *; }
-keep,allowoptimization class fr.androforge.DroidSdStorage$* { public *; }
