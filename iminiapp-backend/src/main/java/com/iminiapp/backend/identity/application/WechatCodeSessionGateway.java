package com.iminiapp.backend.identity.application;

import com.iminiapp.backend.identity.domain.WechatCodeSession;

public interface WechatCodeSessionGateway {

    WechatCodeSession exchange(String loginCode);
}
