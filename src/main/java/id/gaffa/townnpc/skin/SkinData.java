package id.gaffa.townnpc.skin;

import java.util.regex.Pattern;

public record SkinData(String value, String signature) {
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/=]{1,8192}$");

    public static boolean isValid(String value, String signature) {
        return value != null && signature != null
                && BASE64.matcher(value).matches() && BASE64.matcher(signature).matches();
    }
}
