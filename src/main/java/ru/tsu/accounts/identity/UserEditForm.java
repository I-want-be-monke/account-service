package ru.tsu.accounts.identity;


public class UserEditForm {
    private String login;
    private String fullName;
    private long version;

    public static UserEditForm from(UserSummary user) {
        UserEditForm form = new UserEditForm();
        form.login = user.login(); form.fullName = user.fullName(); form.version = user.version();
        return form;
    }
    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
