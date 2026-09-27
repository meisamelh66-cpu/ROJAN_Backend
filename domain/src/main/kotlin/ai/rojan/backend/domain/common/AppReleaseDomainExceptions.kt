package ai.rojan.backend.domain.common

class AppReleaseNotFoundException(identifier: String) :
    DomainException("App release not found: $identifier")

class AppReleaseVersionCodeAlreadyExistsException(applicationId: String, channel: String, versionCode: Int) :
    DomainException("Version code $versionCode already exists for $applicationId ($channel)")

class InvalidApplicationIdException(applicationId: String) :
    DomainException("Unknown application id: $applicationId")

class InvalidAppReleaseStatusTransitionException(from: Enum<*>, to: Enum<*>) :
    DomainException(
        "An app release cannot move from $from to $to" +
            if (from.name == "ARCHIVED" && to.name == "PUBLISHED") " by an edit - use the explicit republish operation" else "",
    )

class PublishedAppReleaseArtifactImmutableException(field: String) :
    DomainException("$field cannot change once a release has been published - publish a new release with a new versionCode instead")
