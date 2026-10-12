# JDBC service entry points; no blanket warning suppression.
-keep class com.microsoft.sqlserver.jdbc.SQLServerDriver { public *; }
-keep class org.mariadb.jdbc.Driver { public *; }
-keep class com.example.datasail.core.StrictTlsPlugin { public *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
