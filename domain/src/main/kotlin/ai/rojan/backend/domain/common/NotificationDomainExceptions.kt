package ai.rojan.backend.domain.common

class NotificationNotFoundException(identifier: String) :
    DomainException("Notification not found: $identifier")
