import numpy as np
from sentence_transformers import SentenceTransformer
import sys

model = SentenceTransformer("all-MiniLM-L6-v2")  # Lightweight, fast, and accurate

intent_anchors = {
    "sql": [
        "What is the revenue of user ID 123?",
        "Give me the count of orders placed last week.",
        "List all users with active subscriptions.",
        "Show me the total sales in January.",
        "Fetch order details for order ID 456."
    ],
    "document": [
        "What is a work order?",
        "How does the system work?",
        "Explain the user onboarding process.",
        "Tell me about customer satisfaction trends.",
        "What happens after the free trial ends?"
    ]
}
duplo_link = "apps.dev.dsuite.tech"
def cosine_similarity(a, b):
    return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))

def get_best_intent(user_prompt):

    if duplo_link in user_prompt:
        return "check_error"

    user_embedding = model.encode(user_prompt)
    best_intent = None
    best_score = -1

    
    for intent, examples in intent_anchors.items():

        scores = [cosine_similarity(user_embedding, model.encode(example)) for example in examples]
        avg_score = sum(scores) / len(scores)

        if avg_score > best_score:
            best_score = avg_score
            best_intent = intent

    return best_intent


def main():
    name = sys.argv[1]
    print(get_best_intent(name))

if __name__ == "__main__":
    main()


