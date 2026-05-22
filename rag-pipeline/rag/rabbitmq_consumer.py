import asyncio
import logging
import os
from typing import Optional

import aio_pika
from aio_pika import IncomingMessage


logger = logging.getLogger("rag_pipeline.rabbitmq")


class RabbitMQConsumer:
    """Simple RabbitMQ consumer that only logs incoming messages."""

    def __init__(self, amqp_url: str, queue_name: str):
        self.amqp_url = amqp_url
        self.queue_name = queue_name
        self.connection: Optional[aio_pika.RobustConnection] = None
        self.channel: Optional[aio_pika.RobustChannel] = None
        self.queue: Optional[aio_pika.Queue] = None
        self._task: Optional[asyncio.Task] = None
        self._stopping = asyncio.Event()

    async def start(self) -> None:
        logger.info("Connecting to RabbitMQ: queue=%s", self.queue_name)
        self.connection = await aio_pika.connect_robust(self.amqp_url)
        logger.info("RabbitMQ connection established")
        self.channel = await self.connection.channel()
        logger.info("RabbitMQ channel opened")
        await self.channel.set_qos(prefetch_count=10)
        self.queue = await self.channel.declare_queue(self.queue_name, durable=True)
        logger.info("RabbitMQ queue declared: %s", self.queue_name)
        self._stopping.clear()
        self._task = asyncio.create_task(self._consume_loop())
        logger.info("RabbitMQ consumer loop started")

    async def stop(self) -> None:
        logger.info("Stopping RabbitMQ consumer")
        self._stopping.set()
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        if self.connection:
            await self.connection.close()
            self.connection = None
        logger.info("RabbitMQ consumer stopped")

    async def _consume_loop(self) -> None:
        if not self.queue:
            return

        async with self.queue.iterator() as queue_iter:
            async for message in queue_iter:
                if self._stopping.is_set():
                    break
                await self._handle_message(message)

    async def _handle_message(self, message: IncomingMessage) -> None:
        async with message.process():
            body = None
            try:
                body = message.body.decode()
                raise NotImplementedError("Message processing not implemented")
            except Exception:
                body = repr(message.body)

            logger.info(
                "RabbitMQ message received: routing_key=%s delivery_tag=%s body=%s",
                getattr(message, "routing_key", None),
                getattr(message, "delivery_tag", None),
                body,
            )


async def create_default_consumer() -> Optional[RabbitMQConsumer]:
    amqp_url = os.getenv("RABBITMQ_URL")
    queue = os.getenv("RABBITMQ_QUEUE", "summarization-queue")
    if not amqp_url:
        logger.warning("RABBITMQ_URL is not set; RabbitMQ consumer will not start")
        return None
    logger.info("RabbitMQ consumer configured for queue=%s", queue)
    return RabbitMQConsumer(amqp_url=amqp_url, queue_name=queue)
