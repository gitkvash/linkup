package ge.kcamp.linkup.social;

import ge.kcamp.linkup.social.entity.Friendship;
import ge.kcamp.linkup.social.entity.FriendshipId;
import ge.kcamp.linkup.social.enums.FriendshipStatus;
import ge.kcamp.linkup.social.exception.FriendRequestNotFoundException;
import ge.kcamp.linkup.social.exception.FriendshipBlockedException;
import ge.kcamp.linkup.social.exception.SelfFriendRequestException;
import ge.kcamp.linkup.social.repository.FriendshipRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ge.kcamp.linkup.identity.UserDirectoryService;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API of the {@code social} module. Friendship is stored as a single undirected
 * edge keyed by a canonically-ordered pair (lower UUID first) to avoid duplicate rows for
 * (a,b) and (b,a).
 */
@Service
public class SocialGraphService {

    private final FriendshipRepository friendshipRepository;
    private final UserDirectoryService userDirectoryService;
    private final ApplicationEventPublisher eventPublisher;

    public SocialGraphService(
            FriendshipRepository friendshipRepository,
            UserDirectoryService userDirectoryService,
            ApplicationEventPublisher eventPublisher) {
        this.friendshipRepository = friendshipRepository;
        this.userDirectoryService = userDirectoryService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void sendFriendRequest(UUID requesterId, UUID targetId) {
        if (requesterId.equals(targetId)) {
            throw new SelfFriendRequestException();
        }

        UUID a = canonicalFirst(requesterId, targetId);
        UUID b = canonicalSecond(requesterId, targetId);
        Optional<Friendship> existing = friendshipRepository.findByIdUserAIdAndIdUserBId(a, b);

        if (existing.isEmpty()) {
            Friendship friendship = new Friendship();
            friendship.setId(pairId(a, b));
            friendship.setStatus(FriendshipStatus.PENDING);
            friendship.setRequestedBy(requesterId);
            friendshipRepository.save(friendship);
            // Tell the recipient. Without this the request was invisible until they
            // happened to open the friends screen.
            eventPublisher.publishEvent(
                    new FriendRequestReceivedEvent(requesterId, targetId, Instant.now()));
            return;
        }

        Friendship friendship = existing.get();
        switch (friendship.getStatus()) {
            case BLOCKED -> throw new FriendshipBlockedException();
            case ACCEPTED -> { /* already friends, no-op */ }
            case PENDING -> {
                if (friendship.getRequestedBy().equals(targetId)) {
                    // The target already requested us - this is a mutual request, auto-accept.
                    friendship.setStatus(FriendshipStatus.ACCEPTED);
                    friendshipRepository.save(friendship);
                    eventPublisher.publishEvent(new FriendshipAcceptedEvent(a, b, Instant.now()));
                }
                // else: requester already has a pending request out to target - no-op.
            }
        }
    }

    @Transactional
    public void acceptFriendRequest(UUID accepterId, UUID requesterId) {
        Friendship friendship = requirePendingRequest(accepterId, requesterId);
        friendship.setStatus(FriendshipStatus.ACCEPTED);
        friendshipRepository.save(friendship);
        eventPublisher.publishEvent(new FriendshipAcceptedEvent(
                canonicalFirst(accepterId, requesterId), canonicalSecond(accepterId, requesterId), Instant.now()));
    }

    @Transactional
    public void declineFriendRequest(UUID declinerId, UUID requesterId) {
        Friendship friendship = requirePendingRequest(declinerId, requesterId);
        friendshipRepository.delete(friendship);
    }

    @Transactional
    public void blockUser(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new SelfFriendRequestException();
        }

        UUID a = canonicalFirst(blockerId, blockedId);
        UUID b = canonicalSecond(blockerId, blockedId);
        Friendship friendship = friendshipRepository.findByIdUserAIdAndIdUserBId(a, b)
                .orElseGet(() -> {
                    Friendship f = new Friendship();
                    f.setId(pairId(a, b));
                    return f;
                });
        friendship.setStatus(FriendshipStatus.BLOCKED);
        friendship.setRequestedBy(blockerId);
        friendshipRepository.save(friendship);
    }

    /**
     * Lifts a block the caller applied, leaving the two as strangers who may request
     * each other again.
     * <p>
     * Blocking used to be a one-way door for <em>both</em> parties: a BLOCKED row makes
     * {@code sendFriendRequest} throw regardless of who set it, {@link #unfriend} only
     * removes ACCEPTED rows, and nothing listed the pair - so a mis-tap on Block ended a
     * friendship permanently, including for the person who did it.
     * <p>
     * Only the blocker may lift it, and anyone else gets the same "no such request" they
     * would get for a pair that was never blocked: telling someone their unblock failed
     * <em>because they are the one who is blocked</em> is exactly what a block is meant
     * to withhold.
     */
    @Transactional
    public void unblockUser(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new SelfFriendRequestException();
        }
        friendshipRepository
                .findByIdUserAIdAndIdUserBId(
                        canonicalFirst(blockerId, blockedId), canonicalSecond(blockerId, blockedId))
                .filter(friendship -> friendship.getStatus() == FriendshipStatus.BLOCKED)
                .filter(friendship -> blockerId.equals(friendship.getRequestedBy()))
                .ifPresentOrElse(friendshipRepository::delete, () -> {
                    throw new FriendRequestNotFoundException();
                });
    }

    /**
     * People the caller has blocked. Deliberately not "blocks involving the caller":
     * being on someone else's list is not something they get to see.
     */
    @Transactional(readOnly = true)
    public List<FriendSummary> getBlockedUsers(UUID userId) {
        List<UUID> blockedIds = friendshipRepository
                .findByStatusForUser(userId, FriendshipStatus.BLOCKED).stream()
                .filter(friendship -> userId.equals(friendship.getRequestedBy()))
                .map(friendship -> otherParty(friendship, userId))
                .toList();

        Map<UUID, String> usernames = userDirectoryService.namesFor(blockedIds);
        return blockedIds.stream()
                .map(id -> new FriendSummary(id, usernames.get(id)))
                .sorted(Comparator.comparing(
                        summary -> summary.username() == null
                                ? ""
                                : summary.username().toLowerCase()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UUID> getAcceptedFriendIds(UUID userId) {
        return friendshipRepository.findFriendIdsByStatus(userId, FriendshipStatus.ACCEPTED);
    }

    @Transactional(readOnly = true)
    public long countAcceptedFriends(UUID userId) {
        return friendshipRepository.countByStatusForUser(userId, FriendshipStatus.ACCEPTED);
    }

    /**
     * Accepted-friend counts for a batch of users, in a single query. Users with no
     * accepted friendships are absent from the map rather than mapped to zero.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> countAcceptedFriendsFor(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : friendshipRepository.countAcceptedFriendsGrouped(userIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Friends with their usernames, so the client doesn't have to show raw ids. */
    @Transactional(readOnly = true)
    public List<FriendSummary> getFriends(UUID userId) {
        List<UUID> friendIds = getAcceptedFriendIds(userId);
        Map<UUID, String> usernames = userDirectoryService.namesFor(friendIds);
        return friendIds.stream()
                .map(id -> new FriendSummary(id, usernames.get(id)))
                .sorted(Comparator.comparing(
                        summary -> summary.username() == null
                                ? ""
                                : summary.username().toLowerCase()))
                .toList();
    }

    /**
     * Unanswered requests in both directions. The client's accept/decline calls
     * already existed but had no data source, so they were unreachable.
     */
    @Transactional(readOnly = true)
    public List<PendingFriendRequest> getPendingRequests(UUID userId) {
        List<Friendship> pending =
                friendshipRepository.findByStatusForUser(userId, FriendshipStatus.PENDING);

        Map<UUID, String> usernames = userDirectoryService.namesFor(
                pending.stream().map(friendship -> otherParty(friendship, userId)).toList());

        return pending.stream()
                .map(friendship -> {
                    UUID otherId = otherParty(friendship, userId);
                    return new PendingFriendRequest(
                            otherId,
                            usernames.get(otherId),
                            // Incoming when the other party initiated it.
                            !userId.equals(friendship.getRequestedBy()));
                })
                .toList();
    }

    /** Removes an accepted friendship. Declining only ever applied to PENDING rows. */
    @Transactional
    public void unfriend(UUID userId, UUID otherId) {
        if (userId.equals(otherId)) {
            throw new SelfFriendRequestException();
        }
        friendshipRepository
                .findByIdUserAIdAndIdUserBId(
                        canonicalFirst(userId, otherId), canonicalSecond(userId, otherId))
                .filter(friendship -> friendship.getStatus() == FriendshipStatus.ACCEPTED)
                .ifPresentOrElse(friendshipRepository::delete, () -> {
                    throw new FriendRequestNotFoundException();
                });
    }

    private static UUID otherParty(Friendship friendship, UUID userId) {
        return friendship.getId().getUserAId().equals(userId)
                ? friendship.getId().getUserBId()
                : friendship.getId().getUserAId();
    }

    private Friendship requirePendingRequest(UUID otherPartyId, UUID requesterId) {
        UUID a = canonicalFirst(otherPartyId, requesterId);
        UUID b = canonicalSecond(otherPartyId, requesterId);
        Friendship friendship = friendshipRepository.findByIdUserAIdAndIdUserBId(a, b)
                .orElseThrow(FriendRequestNotFoundException::new);

        if (friendship.getStatus() != FriendshipStatus.PENDING
                || !requesterId.equals(friendship.getRequestedBy())
                || otherPartyId.equals(requesterId)) {
            throw new FriendRequestNotFoundException();
        }
        return friendship;
    }

    private FriendshipId pairId(UUID a, UUID b) {
        FriendshipId id = new FriendshipId();
        id.setUserAId(a);
        id.setUserBId(b);
        return id;
    }

    private UUID canonicalFirst(UUID x, UUID y) {
        return compareUnsigned(x, y) <= 0 ? x : y;
    }

    private UUID canonicalSecond(UUID x, UUID y) {
        return compareUnsigned(x, y) <= 0 ? y : x;
    }

    /**
     * Orders UUIDs the way PostgreSQL does - byte-wise, unsigned.
     * <p>
     * Not {@link UUID#compareTo}: that compares the two halves as <em>signed</em> longs,
     * so any UUID whose first hex digit is 8-f sorts as negative and lands before one
     * starting 0-7. The pair ordering was self-consistent within Java, so nothing broke -
     * until {@code chk_friendships_canonical_order} tried to state the same invariant in
     * SQL and immediately rejected rows the application considered correctly ordered.
     */
    private static int compareUnsigned(UUID x, UUID y) {
        int high = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    }
}
