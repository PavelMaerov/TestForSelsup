import org.apache.log4j.BasicConfigurator;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        BasicConfigurator.configure(); //чтобы заработал логгер

        //Уточняю задачу, как я ее понял.
        //Момент посылки запроса - это время начала посылки.
        //Время TimeUnit задает скользящее окно (т.е. оно начинается в произвольный момент времени)
        //за которое будет послано не более requestLimit запросов.
        //Т.е. в любое окно длиной TimeUnit должно попасть не более requestLimit моментов начал отправок запросов
        //Здесь все три варианта обеспечивают не более 3х посылок запросов за 1 секунду.

        //CrptApiWithSemaphore crptApi = new CrptApiWithSemaphore(TimeUnit.SECONDS, 3);
        //CrptApiWithSynchronizedBlock crptApi = new CrptApiWithSynchronizedBlock(TimeUnit.SECONDS, 3);
        CrptApiWithScheduledThreadPool crptApi = new CrptApiWithScheduledThreadPool(TimeUnit.MINUTES, 3);
        Doc doc = new Doc();
        Runnable task = ()-> {  //создаем задачу лямбдой
            try {
                crptApi.createDoc(doc, "");
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }
        };
        //запускаем 15 потоков через 0,2 сек
        for (int i = 1; i <= 15; i++) {
            new Thread(task,""+i).start(); //в качестве имени потока - его номер
            sleep(200);
        }
    }
}
