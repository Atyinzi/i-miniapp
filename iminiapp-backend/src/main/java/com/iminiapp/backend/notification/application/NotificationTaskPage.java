package com.iminiapp.backend.notification.application;

import com.iminiapp.backend.notification.domain.NotificationTask;

import java.util.List;

public record NotificationTaskPage(List<NotificationTask> items, long total, int page, int size) {
}
