import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLongArray;

import static java.lang.Thread.currentThread;

/* Третий вариант решения задачи
   Создаем пул задач на отправку запросов - ScheduledThreadPool
   Каждый поток рассчитывает свое разрешенное время отправки.
   Помещает в пул задачу отправки своего запроса c задержкой исполнения, если она требуется.
   Уходит без ожидания чего-либо.

   Казалось бы можно не хранить запланированные моменты отправки предыдущих запросов
   т.к. они уже сохранены в находящихся в пуле задачах.
   Однако это время лежит в приватном поле time, а нам можно вызывать только
   public long getDelay(TimeUnit unit) { return unit.convert(time - System.nanoTime(), NANOSECONDS);}
   Получится, что при обходе очереди мы получим искаженные задержки,
   с неопределенным порядком и главное с неизвестным составом,
   т.к. по документации во время чтения очереди она может изменится.
   Приостановить исполнение пула задач мы не сможем.
   На всякий случай привожу здесь код, читающий эти задержки
   BlockingQueue<Runnable> q = ((ThreadPoolExecutor) executor).getQueue();
   q.forEach(e -> logger.debug("Задержка в очереди " + ((Delayed) e).getDelay(TimeUnit.MILLISECONDS)));

   В силу вышесказанного оставляю в составе класса массив моментов запуска
   из последних поставленных в очередь задач.
   По нему и вычисляю время запуска задачи из текущего потока
 */
public class CrptApiWithScheduledThreadPool { //все поля можно сделать финальными для надежности
    private TimeUnit timeUnit; //инициализируется в конструкторе
    private ObjectMapper objectMapper = new ObjectMapper();

    //чтобы сделать ячейки массива волатильными используем не просто long[], а AtomicLongArray
    private AtomicLongArray sendTimes; //массив для хранения времен отправок. Записывается по кольцу
    private int lastSendTimeIndex = 0; //индекс ячейки массива, хранящей последнюю отправку

    private int nextIndex(int index) { //индекс следующей ячейки массива. Считаем по кольцу
        return (index == sendTimes.length() - 1) ? 0 : index + 1;
    }

    private Logger logger = Logger.getLogger(CrptApiWithScheduledThreadPool.class);
    private String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create"; //может быть параметром конструктора
    private HttpRequest.Builder builder = HttpRequest
            .newBuilder().uri(URI.create(URL)).timeout(Duration.ofSeconds(10));
    private HttpClient client = HttpClient.newHttpClient();
    //постоянно живущих потоков пока не планируем
    private ScheduledExecutorService executor = Executors.newScheduledThreadPool(0);

    public CrptApiWithScheduledThreadPool(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        //Размер массива для хранения времен отправок равен
        //разрешенному количеству отправок в единицу времени.
        sendTimes = new AtomicLongArray(requestLimit);
    }
    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ.
     * Документ и подпись должны передаваться в метод в виде Java объекта и строки соответственно.
     */
    public void createDoc(Doc doc, String signature) throws InterruptedException, IOException {
        String threadName = currentThread().getName();
        logger.debug(LocalTime.now() + " Вызов метода createDoc из потока " + threadName);
        //превращаем объект-параметр в json строку
        String json = objectMapper.writeValueAsString(doc);

        //Готовим запрос в синхронизированном блоке, т.к. методы builder - не синхронизированы
        //и разные потоки могут помешать друг другу
        //Используем мьютекс любого произвольного общего объекта, чтобы разрешить другим потокам
        //исполнять этот блок во время постановки задачи в очередь
        HttpRequest request;
        synchronized (timeUnit) {
            request = builder.header("signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        }
        //Готовим задачу для постановки в пул
        Runnable task = () -> {
            try {
                logger.debug(LocalTime.now() + " Поставленная в очередь задача из потока " + threadName + " начала посылку запроса");
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                logger.debug(LocalTime.now() + " Поставленная в очередь задача из потока " + threadName + " послала запрос");
                logger.trace("response.body() = " + response.body()); //приходит ошибка аутенификакции 401
            } catch (InterruptedException | IOException e) {
                logger.error(LocalTime.now() + " Ошибка при посылке запроса из задачи потока " + threadName);
            }
        };

        //Следующий синхронизированный блок нужен для правильного расчета времени запуска,
        //который обращается к общему массиву для чтения и записи
        long waitingTime;
        synchronized (this) {
            logger.debug(LocalTime.now() + " Поток " + threadName + " зашел в синхронизированный блок для постановки задачи в очередь");
            //Определяем величину задержки перед отправкой,
            //требуемой для соблюдения количества отправок в единицу времени.
            //Для этого посмотрим на ячейку массива, следующую после последней отправки
            //Там лежит время отправки запроса, отправленного  requestLimit попыток назад от текущего.
            //Это самое старое время из всех хранимых в массиве (из-за кольцевой формы его записи)
            //Текущая отправка должна произойти не раньше, чем TimeUnit после этого времени.
            //Задержка в пуле хранится с точностью до наносекунд. Так и считаем, и записываем
            long now = System.nanoTime();
            long elapsedTime = now - sendTimes.get(nextIndex(lastSendTimeIndex));
            waitingTime = timeUnit.toNanos(1) - elapsedTime;
            waitingTime = (waitingTime < 0) ? 0 : waitingTime;

            //сдвигаем вперед указатель элемента массива для последней посылки запроса
            lastSendTimeIndex = nextIndex(lastSendTimeIndex);
            //записываем в массив время будущего запуска запроса
            sendTimes.set(lastSendTimeIndex, now + waitingTime);
            executor.schedule(task, waitingTime, TimeUnit.NANOSECONDS);
        }  //конец синхронизированного блока
        logger.debug(LocalTime.now() + " Поток " + threadName +
                " поставил задачу в очередь на "+LocalTime.now().plusNanos(waitingTime)+
                ". Планируемая задержка - " + waitingTime + " нс");
    }
    //Обследование показало, что точность запуска назначенных заданий весьма невелика
    //и отставание от плана может быть несколько десятых секунды
}

