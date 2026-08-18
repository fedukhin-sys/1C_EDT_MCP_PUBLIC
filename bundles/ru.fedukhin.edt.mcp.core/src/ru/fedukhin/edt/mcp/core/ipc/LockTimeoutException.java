package ru.fedukhin.edt.mcp.core.ipc;

/**
 * Ресурс занят другим процессом (или другим потоком этого процесса).
 *
 * <p>Сообщение содержит описание держателя — кто, из какой рабочей области и как
 * давно держит замок. Это принципиально: без него отказ выглядел бы как очередной
 * «timeout after 600s», по которому непонятно, что делать.
 */
public class LockTimeoutException extends Exception {

    private static final long serialVersionUID = 1L;

    public LockTimeoutException(String message) {
        super(message);
    }
}
