package com.example.user.domain;

import java.time.LocalDateTime;

public class User {

  private Long id;
  private String nickname;
  private String password;
  private String salt;
  private String head;
  private LocalDateTime registerDate;
  private LocalDateTime lastLoginDate;
  private Integer loginCount;

  // 每个字段一对 get / set，和 Goods.java 一样
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getNickname() {
    return nickname;
  }

  public void setNickname(String nickname) {
    this.nickname = nickname;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getSalt() {
    return salt;
  }

  public void setSalt(String salt) {
    this.salt = salt;
  }

  public String getHead() {
    return head;
  }

  public void setHead(String head) {
    this.head = head;
  }

  public LocalDateTime getRegisterDate() {
    return registerDate;
  }

  public void setRegisterDate(LocalDateTime registerDate) {
    this.registerDate = registerDate;
  }

  public LocalDateTime getLastLoginDate() {
    return lastLoginDate;
  }

  public void setLastLoginDate(LocalDateTime lastLoginDate) {
    this.lastLoginDate = lastLoginDate;
  }

  public Integer getLoginCount() {
    return loginCount;
  }

  public void setLoginCount(Integer loginCount) {
    this.loginCount = loginCount;
  }
}
