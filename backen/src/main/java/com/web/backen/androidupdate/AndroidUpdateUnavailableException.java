package com.web.backen.androidupdate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
final class AndroidUpdateUnavailableException extends RuntimeException {
}
