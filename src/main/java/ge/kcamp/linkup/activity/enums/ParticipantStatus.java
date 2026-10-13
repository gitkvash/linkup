package ge.kcamp.linkup.activity.enums;

public enum ParticipantStatus {
    INVITED,
    JOINED,
    DECLINED,
    /**
     * "Might come". Answered on purpose, so unlike INVITED it is not a question still open,
     * and unlike JOINED it takes no place: it is not counted in {@code participantCount} and
     * does not count against {@code maxParticipants}. Still hears about the plan changing,
     * starting or being cancelled - see {@code ParticipantRepository.IN_THE_PLAN}.
     */
    MAYBE
}
