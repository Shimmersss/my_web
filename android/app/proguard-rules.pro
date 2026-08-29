# GeckoView supplies its own consumer rules. The Shimmer bridge is a built-in
# WebExtension and exposes no Java object directly to page JavaScript.

# SnakeYAML exposes optional desktop-Java Bean introspection branches. Android
# does not provide java.beans; GeckoView does not use those branches at runtime.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
