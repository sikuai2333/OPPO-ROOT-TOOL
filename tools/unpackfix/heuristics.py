"""Heuristics that separate packer extraction from a class that legitimately uses JNI."""
import re

# A legitimately native-backed class usually follows one of these shapes:
#   - every method is native (pure wrapper, e.g. YogaNative)
#   - the native methods are named after their Java twin (nXxx / nativeXxx / jni_Xxx)
#   - the class lives in a JDK/framework namespace
LEGIT_NATIVE_PREFIXES = (
    'n', 'native', 'jni_', 'jni', 'Natives', '_n',
)

FRAMEWORK_PREFIXES = (
    'Ljava/',
    'Landroid/',
    'Landroidx/',
    'Ldalvik/',
    'Ljdk/',
    'Lsun/',
    'Lorg/',
    'Lkotlin/',
    'Lcom/facebook/',
    'Lcom/tencent/mmkv/',
    'Lio/realm/',
    'Lcom/google/',
)

# A lifecycle method being native is a strong packer signal: frameworks never
# implement onCreate/onResume in C.
LIFECYCLE_NAMES = {
    'onCreate', 'onStart', 'onResume', 'onPause', 'onStop', 'onDestroy',
    'onRestart', 'attachBaseContext', 'onActivityResult', 'onNewIntent',
    'initView', 'initData', 'onCreateView', 'onViewCreated',
}

# --- packer fingerprints ---------------------------------------------------
SHELL_CLASS_PREFIXES = {
    'com.stub.': '360',
    'com.qihoo.': '360',
    'com.jg.': '360',
    'com.secneo.': 'bangcle',
    'com.secshell.': 'bangcle',
    'com.tencent.StubShell.': 'tencent-legu',
    'com.netease.nis.wrapper.': 'netease',
    'com.baidu.protect.': 'baidu',
    'com.ali.mobisecenhance.': 'alibaba',
    'com.ali.mobisec.': 'alibaba',
    'com.ijiami.': 'ijiami',
    'com.nqshield.': 'nqshield',
    'com.egis.': 'payegis',
    'com.payegis.': 'payegis',
}

SHELL_FILES = {
    'libjiagu.so': '360', 'libjiagu_a64.so': '360', 'libjiagu_art.so': '360',
    'libjiagu_x86.so': '360', 'jiagu.dat': '360',
    'libDexHelper.so': 'bangcle', 'libSecShell.so': 'bangcle',
    'libsecexe.so': 'bangcle', 'libsecmain.so': 'bangcle',
    'libshellx-super.2019.so': 'tencent-legu', 'libtup.so': 'tencent-legu',
    'libnesec.so': 'netease',
    'libbaiduprotect.so': 'baidu',
    'libmobisec.so': 'alibaba',
    'libtongfudun.so': 'payegis', 'libegis.so': 'payegis',
    'libnqshield.so': 'nqshield',
}


def shell_vendor(dotted_class):
    """Return the packer vendor owning this class, or None."""
    for prefix, vendor in SHELL_CLASS_PREFIXES.items():
        if dotted_class.startswith(prefix):
            return vendor
    return None


def classify_native(class_desc, coded_count, native_names):
    """Return (kind, confidence, reason) for a class that mixes Java and native code.

    kind:
      'shell'        - the packer's own class, not app code
      'extracted'    - very likely a method the packer moved into native
      'wrapper'      - legitimate JNI wrapper
      'normal'       - nothing to report
    """
    dotted = class_desc[1:-1].replace('/', '.') if class_desc.startswith('L') else class_desc
    if shell_vendor(dotted):
        return 'shell', 'high', 'packer-namespace'
    if not native_names:
        return 'normal', '', ''

    natives = sorted(set(native_names))
    lifecycle = sorted(set(natives) & LIFECYCLE_NAMES)
    if lifecycle:
        # The app's own lifecycle being native is the strongest signal there is.
        return 'extracted', 'high', 'lifecycle-native:' + ','.join(lifecycle)

    if coded_count == 0:
        return 'wrapper', '', 'all-native'

    for prefix in FRAMEWORK_PREFIXES:
        if class_desc.startswith(prefix):
            return 'wrapper', '', 'framework-namespace'

    conventional = sum(1 for n in natives
                       if any(n.startswith(p) for p in LEGIT_NATIVE_PREFIXES))
    if conventional * 2 >= len(natives):
        return 'wrapper', '', 'conventional-native-names'
    return 'extracted', 'low', 'unconventional-native-names'


def looks_like_shell_native(class_desc, coded_count, native_names, total_methods=0):
    """Backwards-compatible wrapper: True when the class is likely extracted."""
    kind, _confidence, reason = classify_native(class_desc, coded_count, native_names)
    return kind == 'extracted', reason
