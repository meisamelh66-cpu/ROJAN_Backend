package ai.rojan.backend.domain.common

class AppReleaseNotFoundException(identifier: String) :
    DomainException("App release not found: $identifier")

class AppReleaseVersionCodeAlreadyExistsException(applicationId: String, versionCode: Int) :
    DomainException("Version code $versionCode already exists for $applicationId")

class InvalidApplicationIdException(applicationId: String) :
    DomainException("Unknown application id: $applicationId")
