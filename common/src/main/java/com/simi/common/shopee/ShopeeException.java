package com.simi.common.shopee;

/** Deliberately contains only safe, fixed operator messages. */
public class ShopeeException extends RuntimeException {
    private final int status;
    public ShopeeException(int status, String message) {
        super(message);
        this.status = status;
    }
    public int status() { return status; }
}
