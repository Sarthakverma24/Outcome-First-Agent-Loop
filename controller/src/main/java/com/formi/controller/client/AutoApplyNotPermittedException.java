package com.formi.controller.client;

/**
 * 403 auto_apply_not_permitted — the requested PUT /workflows/{id}/config is not
 * allowed under the workflow's governance policy (wrong dims, shift too large, etc.).
 */
public class AutoApplyNotPermittedException extends ApiException {
    public AutoApplyNotPermittedException(String body) {
        super(403, "auto_apply_not_permitted", body);
    }
}
