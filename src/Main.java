import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        CrptApiWithSynchronisedBlock crptApi = new CrptApiWithSynchronisedBlock(TimeUnit.SECONDS, 3);
        Doc doc = new Doc();
        Runnable task = ()-> {
            try {
                crptApi.createDoc(doc, "");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
        for (int i = 1; i <= 15; i++) {
            new Thread(task,""+i).start(); //в качестве имени потока - его номер
            sleep(200);
        }
    }
}
