package bedwars;

/** Sidebar 解析结果，对应源码 SidebarSnapshot。 */
public final class SidebarSnapshot {

    public int teamsMask;
    public Team ownTeam = Team.UNKNOWN;
    public int distinctTeams;
    public int youRows;
    public boolean valid;

    public boolean hasTeam(Team team) {
        final int index = team.index();
        return index >= 0 && (teamsMask & (1 << index)) != 0;
    }

    @Override
    public String toString() {
        return "SidebarSnapshot{mask=" + Integer.toBinaryString(teamsMask)
                + ", ownTeam=" + ownTeam + ", distinct=" + distinctTeams
                + ", youRows=" + youRows + ", valid=" + valid + '}';
    }
}
