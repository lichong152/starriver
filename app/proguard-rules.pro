# 星川使用默认 R8 规则即可：
# - 无反射、无 JNI、无序列化库
# - 四大组件（MainActivity / GuardService / BootReceiver）由 AGP 按 Manifest 自动 keep
# - 无障碍服务 meta-data 与 XML 资源由 aapt/R8 保留
# 若日后引入需要 keep 规则的依赖，在此追加，不要为了省事加 -dontobfuscate。
