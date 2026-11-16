# Keep DeviceAdminReceiver, VpnService, AccessibilityService entry points referenced from the manifest/system.
-keep class com.kushertil.filter.admin.KosherAdminReceiver { *; }
-keep class com.kushertil.filter.vpn.FilterVpnService { *; }
-keep class com.kushertil.filter.access.BlockAccessibilityService { *; }
-keep class com.kushertil.filter.boot.BootReceiver { *; }
-keepattributes *Annotation*
