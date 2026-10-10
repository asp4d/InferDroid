package dev.inferdroid.speech;

import java.util.Set;

public final class SpeechLanguages {
    private static final Set<String> CODES = Set.of(("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su").split(" "));
    private SpeechLanguages() { }
    public static boolean supports(String code) { return code.isEmpty() || CODES.contains(code); }
}
