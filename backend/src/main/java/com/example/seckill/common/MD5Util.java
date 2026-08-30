package com.example.seckill.common;

import org.apache.commons.codec.digest.DigestUtils;

public final class MD5Util {

  private static final String SALT = "1a2b3c4d";

  private MD5Util() {}

  public static String md5(String src) {
    return DigestUtils.md5Hex(src);
  }

  public static String inputPassToFormPass(String inputPass) {
    String str = "" + SALT.charAt(0) + SALT.charAt(2) + inputPass
        + SALT.charAt(5) + SALT.charAt(4);
    return md5(str);
  }

  public static String formPassToDbPass(String formPass, String salt) {
    String str = "" + salt.charAt(0) + salt.charAt(2) + formPass
        + salt.charAt(5) + salt.charAt(4);
    return md5(str);
  }

  public static String inputPassToDbPass(String inputPass, String saltDb) {
    return formPassToDbPass(inputPassToFormPass(inputPass), saltDb);
  }
}