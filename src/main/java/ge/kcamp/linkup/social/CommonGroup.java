package ge.kcamp.linkup.social;

import java.util.UUID;

/** A group two people are both in, with how many members it has. */
public record CommonGroup(UUID groupId, String groupName, long memberCount) {
}
