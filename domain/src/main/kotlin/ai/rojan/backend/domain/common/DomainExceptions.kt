package ai.rojan.backend.domain.common

sealed class DomainException(message: String) : RuntimeException(message)

class EmailAlreadyRegisteredException(email: String) :
    DomainException("An account with email '$email' already exists")

class InvalidCredentialsException :
    DomainException("Invalid email or password")

class InvalidTokenException :
    DomainException("Token is invalid or expired")

class UserNotFoundException(identifier: String) :
    DomainException("User not found: $identifier")

class InactiveUserException(identifier: String) :
    DomainException("User account is inactive: $identifier")
